import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, setUnauthorizedHandler } from './api.js'
import { AuthContext } from './authContext.js'

/** The backend owns the session (httpOnly cookie); this only mirrors who is signed in. */
export function AuthProvider({ children }) {
  const [user, setUser] = useState(undefined) // undefined while the first check runs

  useEffect(() => {
    setUnauthorizedHandler(() => setUser(null))
    api.get('/auth/me').then(setUser, () => setUser(null))
  }, [])

  const login = useCallback(async (email, password) => {
    const signedIn = await api.post('/auth/login', { email, password })
    setUser(signedIn)
    return signedIn
  }, [])

  const register = useCallback(async (details) => {
    const registered = await api.post('/auth/register', details)
    setUser(registered)
    return registered
  }, [])

  const logout = useCallback(async () => {
    await api.post('/auth/logout').catch(() => {})
    setUser(null)
  }, [])

  const value = useMemo(() => ({ user, login, register, logout }), [user, login, register, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
