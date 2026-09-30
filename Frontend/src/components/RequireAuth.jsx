import { Navigate, Outlet, useLocation } from 'react-router'
import { homeFor, useAuth } from '../authContext.js'
import { ErrorNotice } from './ui.jsx'

/** Layout route: signed-in users with an allowed role see the child page; others are redirected. */
export function RequireAuth({ roles }) {
  const { user, checkError, checkSession } = useAuth()
  const location = useLocation()
  if (user === undefined && checkError) {
    return (
      <div className="page">
        <ErrorNotice error={checkError} />
        <button type="button" className="sign sign--plain" style={{ width: 'fit-content' }} onClick={checkSession}>
          Try again
        </button>
      </div>
    )
  }
  if (user === undefined) {
    return <p className="page muted" aria-busy="true">Checking your session…</p>
  }
  if (!user) {
    return <Navigate to="/signin" replace state={{ from: location }} />
  }
  if (roles && !roles.includes(user.role)) {
    return <Navigate to={homeFor(user)} replace />
  }
  return <Outlet />
}
