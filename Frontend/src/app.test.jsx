import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route as RouterRoute, Routes, useLocation } from 'react-router'
import { api, ApiError, setUnauthorizedHandler } from './api.js'
import { AuthContext } from './authContext.js'
import { ComplaintActions } from './components/ComplaintActions.jsx'
import { RequireAuth } from './components/RequireAuth.jsx'
import { Route } from './components/ui.jsx'
import ComplaintDetail from './pages/ComplaintDetail.jsx'
import MyComplaints from './pages/MyComplaints.jsx'
import SignIn from './pages/SignIn.jsx'
import { complaintNumber } from './format.js'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

const complaint = (status, actions) => ({ id: 7, status, actions, proofs: [], proofsRemaining: 5 })
const buttons = () => screen.queryAllByRole('button').map((b) => [b.textContent, b.classList.contains('sign--go')])

describe('the next step on a complaint', () => {
  it('asks a citizen to confirm a resolved fix, with reopening as the alternative', () => {
    render(<ComplaintActions complaint={complaint('RESOLVED', ['REOPEN', 'FEEDBACK'])} user={{ role: 'CITIZEN' }} onDone={() => {}} />)
    expect(buttons()).toEqual([
      ['Confirm the fix', true],
      ['It is not fixed: reopen', false],
    ])
  })

  it('makes assigning the next step only while a complaint waits to be assigned', () => {
    const admin = { role: 'ADMIN' }
    render(<ComplaintActions complaint={complaint('SUBMITTED', ['ASSIGN', 'EDIT'])} user={admin} onDone={() => {}} />)
    expect(buttons()[0]).toEqual(['Assign', true])
    cleanup()

    render(<ComplaintActions complaint={complaint('ASSIGNED', ['ASSIGN', 'EDIT'])} user={admin} onDone={() => {}} />)
    expect(buttons()).toEqual([
      ['Reassign', false],
      ['Edit category or priority', false],
    ])
  })

  it('tells an officer what evidence is missing before resolving', () => {
    render(<ComplaintActions complaint={complaint('IN_PROGRESS', ['ADD_NOTE', 'REASSIGN', 'UPLOAD_PROOF'])} user={{ role: 'OFFICER' }} onDone={() => {}} />)
    screen.getByText(/add an investigation note and upload at least one photo/)
    expect(buttons()[0]).toEqual(['Upload resolution proof', true])
  })

  it('offers an officer the proof slots the server says are left in this round', () => {
    const inProgress = { ...complaint('IN_PROGRESS', ['UPLOAD_PROOF']), proofs: [{}, {}, {}, {}, {}], proofsRemaining: 2 }
    render(<ComplaintActions complaint={inProgress} user={{ role: 'OFFICER' }} onDone={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: 'Upload resolution proof' }))
    screen.getByText('JPEG, PNG or WebP, up to 5 MB each. You can add 2 more.')
  })

  it('tells an officer who hands a complaint over that it has left their queue', async () => {
    const handedOver = { ...complaint('ASSIGNED', []), assignedOfficer: { id: 9, name: 'Ravi Kumar' } }
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url, init) => {
        if (init.method === 'POST') return Response.json(handedOver)
        return Response.json(String(url).includes('/officers') ? [{ id: 9, name: 'Ravi Kumar' }] : [{ id: 30, name: 'Street Lighting' }])
      }),
    )
    const onDone = vi.fn()
    const assigned = { ...complaint('ASSIGNED', ['START', 'REASSIGN']), departmentId: 30 }
    render(<ComplaintActions complaint={assigned} user={{ id: 4, role: 'OFFICER' }} onDone={onDone} />)
    fireEvent.click(screen.getByRole('button', { name: 'Reassign' }))
    fireEvent.change(await screen.findByLabelText('Officer'), { target: { value: '9' } })
    await screen.findByRole('option', { name: 'Ravi Kumar' })
    fireEvent.change(screen.getByLabelText('Officer'), { target: { value: '9' } })
    fireEvent.click(screen.getByRole('button', { name: 'Assign to officer' }))

    await vi.waitFor(() => expect(onDone).toHaveBeenCalledWith(handedOver, 'Handed over. The complaint has left your queue.'))
  })

  it('tells apart officers who share a name by their email', async () => {
    const officers = [
      { id: 9, name: 'Ravi Kumar', email: 'ravi.kumar@city.example' },
      { id: 10, name: 'Ravi Kumar', email: 'r.kumar2@city.example' },
      { id: 11, name: 'Neha Singh', email: 'neha.singh@city.example' },
    ]
    vi.stubGlobal('fetch', vi.fn(async (url) => Response.json(String(url).includes('/officers') ? officers : [{ id: 30, name: 'Roads' }])))
    render(<ComplaintActions complaint={{ ...complaint('SUBMITTED', ['ASSIGN']), departmentId: 30 }} user={{ id: 1, role: 'ADMIN' }} onDone={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: 'Assign' }))

    await screen.findByRole('option', { name: 'Ravi Kumar (ravi.kumar@city.example)' })
    screen.getByRole('option', { name: 'Ravi Kumar (r.kumar2@city.example)' })
    screen.getByRole('option', { name: 'Neha Singh' })
  })

  it('shows nothing when this person has no action on the complaint', () => {
    const { container } = render(<ComplaintActions complaint={complaint('ASSIGNED', [])} user={{ role: 'CITIZEN' }} onDone={() => {}} />)
    expect(container.innerHTML).toBe('')
  })
})

describe('the route strip', () => {
  it('marks passed stations, signs the current one and fills the line to it', () => {
    render(<Route status="IN_PROGRESS" />)
    const stops = screen.getAllByRole('listitem')
    expect(stops.map((stop) => stop.className.trim())).toEqual(['route__stop is-passed', 'route__stop is-passed', 'route__stop is-current', 'route__stop', 'route__stop'])
    expect(stops[2].getAttribute('aria-current')).toBe('step')
    expect(screen.getByRole('list').style.getPropertyValue('--progress')).toBe('0.5')
  })
})

describe('the API client', () => {
  it('turns a problem response into a readable error with field messages', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ detail: 'Validation failed', errors: { email: 'is already registered' } }, { status: 400 })))
    const error = await api.post('/auth/register', {}).catch((e) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect([error.status, error.message, error.fieldErrors.email]).toEqual([400, 'Validation failed', 'is already registered'])
  })

  it('signs the user out when the session expires, but not on a failed sign-in', async () => {
    const signOut = vi.fn()
    setUnauthorizedHandler(signOut)
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 401 })))
    await api.post('/auth/login', {}).catch(() => {})
    expect(signOut).not.toHaveBeenCalled()
    await api.get('/complaints').catch(() => {})
    expect(signOut).toHaveBeenCalledOnce()
  })

  it('explains a network failure instead of surfacing a TypeError', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))))
    const error = await api.get('/complaints').catch((e) => e)
    expect([error.status, error.message]).toEqual([0, 'Cannot reach FixMyCity. Check your connection and try again.'])
  })
})

it('numbers complaints like a plate', () => {
  expect([complaintNumber(7), complaintNumber(123456)]).toEqual(['FMC-00007', 'FMC-123456'])
})

describe('failure states people can understand', () => {
  it('explains a gateway error instead of a generic failure', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>Bad Gateway</html>', { status: 502 })))
    const error = await api.get('/complaints').catch((e) => e)
    expect(error.message).toBe('FixMyCity is temporarily unavailable. Please try again in a moment.')
  })

  it('uploads only the photos the picker shows after a rejected choice', () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    render(<ComplaintActions complaint={complaint('IN_PROGRESS', ['UPLOAD_PROOF'])} user={{ role: 'OFFICER' }} onDone={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: 'Upload resolution proof' }))
    const picker = screen.getByLabelText('Photos of the finished work')
    fireEvent.change(picker, { target: { files: [new File(['x'], 'done.jpg', { type: 'image/jpeg' })] } })
    fireEvent.change(picker, { target: { files: [new File(['x'], 'notes.pdf', { type: 'application/pdf' })] } })

    screen.getByText('notes.pdf is not a JPEG, PNG or WebP photo of 5 MB or less.')
    fireEvent.click(screen.getByRole('button', { name: 'Upload photos' }))
    screen.getByText('Choose at least one photo.')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('tells people when their session has ended', () => {
    render(
      <AuthContext.Provider value={{ user: null, login: vi.fn(), sessionEnded: true }}>
        <MemoryRouter>
          <SignIn />
        </MemoryRouter>
      </AuthContext.Provider>,
    )
    screen.getByText('Your session has ended. Please sign in again.')
  })

  it('offers a retry when the session check cannot reach the server', () => {
    const checkSession = vi.fn()
    const offline = new ApiError(0, 'Cannot reach FixMyCity. Check your connection and try again.')
    render(
      <AuthContext.Provider value={{ user: undefined, checkError: offline, checkSession }}>
        <MemoryRouter>
          <RequireAuth />
        </MemoryRouter>
      </AuthContext.Provider>,
    )
    screen.getByText('Cannot reach FixMyCity. Check your connection and try again.')
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(checkSession).toHaveBeenCalledOnce()
  })
})

const detail = (overrides = {}) => ({
  id: 7,
  title: 'Streetlight out on Park Street',
  description: 'The streetlight opposite house 14 has been off for five nights.',
  location: 'Park Street',
  categoryId: 3,
  categoryName: 'Streetlights',
  departmentId: 30,
  departmentName: 'Street Lighting & Electrical',
  status: 'SUBMITTED',
  priority: null,
  citizen: { id: 1, name: 'Asha Rao', phone: null },
  assignedOfficer: null,
  ai: { status: 'PENDING', categoryId: null, categoryName: null, priority: null, departmentId: null, departmentName: null },
  images: [],
  proofs: [],
  timeline: [],
  notes: [],
  feedback: null,
  actions: [],
  proofsRemaining: 5,
  createdAt: '2026-10-01T10:00:00Z',
  updatedAt: '2026-10-01T10:00:00Z',
  ...overrides,
})

const analysed = { status: 'COMPLETED', categoryId: null, categoryName: null, priority: 'LOW', departmentId: null, departmentName: null, summary: 'Light is out.' }

function LocationState() {
  return <output data-testid="location-state">{JSON.stringify(useLocation().state)}</output>
}

function renderDetail(entry = '/complaints/7') {
  return render(
    <AuthContext.Provider value={{ user: { id: 1, role: 'CITIZEN', name: 'Asha Rao' } }}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <RouterRoute path="/complaints/:id" element={<ComplaintDetail />} />
        </Routes>
        <LocationState />
      </MemoryRouter>
    </AuthContext.Provider>,
  )
}

describe('the complaint page', () => {
  it('checks AI analysis often at first, then slowly, and keeps checking after a failed attempt', async () => {
    vi.useFakeTimers()
    let failing = false
    const fetch = vi.fn(async () => (failing ? new Response(null, { status: 500 }) : Response.json(detail())))
    vi.stubGlobal('fetch', fetch)
    renderDetail()
    await act(() => vi.advanceTimersByTimeAsync(0))
    expect(fetch).toHaveBeenCalledTimes(1)

    for (let poll = 0; poll < 15; poll++) await act(() => vi.advanceTimersByTimeAsync(4000))
    expect(fetch).toHaveBeenCalledTimes(16)

    await act(() => vi.advanceTimersByTimeAsync(4000))
    expect(fetch).toHaveBeenCalledTimes(16) // after a minute it waits 30 seconds between checks

    failing = true
    await act(() => vi.advanceTimersByTimeAsync(26000))
    expect(fetch).toHaveBeenCalledTimes(17)
    await act(() => vi.advanceTimersByTimeAsync(30000))
    expect(fetch).toHaveBeenCalledTimes(18) // the failed check did not end the polling
  })

  it('stops checking a complaint that is no longer visible to this person', async () => {
    vi.useFakeTimers()
    let visible = true
    const fetch = vi.fn(async () => (visible ? Response.json(detail()) : new Response(null, { status: 404 })))
    vi.stubGlobal('fetch', fetch)
    renderDetail()
    await act(() => vi.advanceTimersByTimeAsync(0))

    visible = false // handed to another officer while the analysis was still running
    await act(() => vi.advanceTimersByTimeAsync(4000))
    expect(fetch).toHaveBeenCalledTimes(2)
    await act(() => vi.advanceTimersByTimeAsync(120000))
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('never asks the server for an address that is not a complaint number', () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    renderDetail('/complaints/12;drop')

    screen.getByText('This complaint does not exist or is not visible to you.')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('shows the submitted confirmation once and clears it from history', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json(detail({ ai: analysed }))))
    renderDetail({ pathname: '/complaints/7', state: { justSubmitted: true } })

    await screen.findByText('Complaint submitted. It will be analysed and assigned to the right department.')
    expect(screen.getByTestId('location-state').textContent).toBe('null')
  })

  it('says so when the category or department the AI suggested no longer exists', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json(detail({ ai: analysed }))))
    renderDetail()

    expect(await screen.findAllByText('No longer available')).toHaveLength(2)
  })
})

describe('answers the app cannot use', () => {
  it('reports an answer that is not JSON instead of treating it as empty data', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<!doctype html><html></html>', { status: 200 })))
    const error = await api.get('/complaints').catch((e) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error.message).toBe('FixMyCity is not responding correctly. Please try again in a moment.')
  })

  it('accepts an empty body as a valid answer', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 202 })))
    expect(await api.post('/complaints/7/analysis/retry')).toBeNull()
  })

  it('reloads the complaint when an action conflicts with its current state', async () => {
    const conflict = { detail: 'Only assigned complaints can be started.' }
    vi.stubGlobal('fetch', vi.fn(async () => Response.json(conflict, { status: 409 })))
    const onStale = vi.fn()
    render(<ComplaintActions complaint={complaint('ASSIGNED', ['START'])} user={{ role: 'OFFICER' }} onDone={() => {}} onStale={onStale} />)
    fireEvent.click(screen.getByRole('button', { name: 'Start work' }))

    await screen.findByText(conflict.detail)
    expect(onStale).toHaveBeenCalledOnce()
  })

  it('does not claim a stage is empty when the list failed to load', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 500 })))
    render(
      <MemoryRouter>
        <MyComplaints />
      </MemoryRouter>,
    )
    fireEvent.click(screen.getByLabelText('Submitted'))

    await screen.findByText('Something went wrong. Please try again.')
    expect(screen.queryByText('Nothing submitted')).toBeNull()
  })
})
