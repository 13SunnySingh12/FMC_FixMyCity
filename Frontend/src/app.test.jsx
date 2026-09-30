import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { api, ApiError, setUnauthorizedHandler } from './api.js'
import { ComplaintActions } from './components/ComplaintActions.jsx'
import { Route } from './components/ui.jsx'
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
