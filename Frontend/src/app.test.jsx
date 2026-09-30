import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { api, ApiError, setUnauthorizedHandler } from './api.js'
import { AuthContext } from './authContext.js'
import { ComplaintActions } from './components/ComplaintActions.jsx'
import { RequireAuth } from './components/RequireAuth.jsx'
import { Route } from './components/ui.jsx'
import SignIn from './pages/SignIn.jsx'
import { complaintNumber } from './format.js'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const complaint = (status, actions) => ({ id: 7, status, actions, proofs: [] })
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
