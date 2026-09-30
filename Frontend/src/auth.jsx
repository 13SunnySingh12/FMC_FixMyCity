import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, setUnauthorizedHandler } from './api.js'
import { AuthContext } from './authContext.js'

/** The backend owns the session (httpOnly cookie); this only mirrors who is signed in. */
export function AuthProvider({ children }) {
  const [user, setUser] = useState(undefined) // undefined while the first check runs
  const [checkError, setCheckError] = useState(null) // the server could not be reached for that check
  const [sessionEnded, setSessionEnded] = useState(false)

  // Only a 401 means "signed out"; an outage keeps the user undefined so pages can offer a retry.
  const checkSession = useCallback(
    () =>
      api.get('/auth/me').then(
        (me) => {
          setCheckError(null)
          setUser(me)
        },
        (error) => {
          if (error.status === 401) {
            setCheckError(null)
            setUser(null)
          } else {
            setCheckError(error)
          }
        },
      ),
    [],
  )

  useEffect(() => {
    // A 401 on any other call means the session expired or the account was deactivated.
    setUnauthorizedHandler(() => {
      setUser(null)
      setSessionEnded(true)
    })
    checkSession()
  }, [checkSession])

  const login = useCallback(async (email, password) => {
    const signedIn = await api.post('/auth/login', { email, password })
    setSessionEnded(false)
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
    setSessionEnded(false)
    setUser(null)
  }, [])

  const value = useMemo(
    () => ({ user, login, register, logout, sessionEnded, checkError, checkSession }),
    [user, login, register, logout, sessionEnded, checkError, checkSession],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
