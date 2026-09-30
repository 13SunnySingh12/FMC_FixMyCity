/** Thin fetch wrapper for the FMC backend. The login cookie travels automatically (same origin). */

export class ApiError extends Error {
  constructor(status, message, fieldErrors = {}) {
    super(message)
    this.status = status
    this.fieldErrors = fieldErrors
  }
}

let onUnauthorized = () => {}

/** The auth layer registers a handler so an expired session signs the user out everywhere. */
export function setUnauthorizedHandler(handler) {
  onUnauthorized = handler
}

async function request(method, path, { body, params, form } = {}) {
  const query = params ? `?${new URLSearchParams(Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== ''))}` : ''
  const init = { method, headers: { Accept: 'application/json' } }
  if (form) {
    init.body = form
  } else if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }

  let response
  try {
    response = await fetch(`/api${path}${query}`, init)
  } catch {
    throw new ApiError(0, 'Cannot reach FixMyCity. Check your connection and try again.')
  }

  if (response.status === 401 && !path.startsWith('/auth/')) {
    onUnauthorized()
  }
  if (response.status === 204) {
    return null
  }
  const data = await response.json().catch(() => null)
  if (!response.ok) {
    throw new ApiError(response.status, data?.detail || fallbackMessage(response.status), data?.errors || {})
  }
  return data
}

function fallbackMessage(status) {
  if (status === 401) return 'Please sign in to continue.'
  if (status === 403) return 'You do not have access to this.'
  if (status === 404) return 'Not found.'
  if (status === 413) return 'That file is too large. Each photo must be 5 MB or smaller.'
  if (status === 429) return 'Too many requests. Please wait a minute and try again.'
  if (status === 502 || status === 503 || status === 504) return 'FixMyCity is temporarily unavailable. Please try again in a moment.'
  return 'Something went wrong. Please try again.'
}

export const api = {
  get: (path, params) => request('GET', path, { params }),
  post: (path, body) => request('POST', path, { body }),
  put: (path, body) => request('PUT', path, { body }),
  patch: (path, body) => request('PATCH', path, { body }),
  delete: (path) => request('DELETE', path),
  upload: (path, form) => request('POST', path, { form }),
}
