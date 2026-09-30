import { Link } from 'react-router'
import { homeFor, useAuth } from '../authContext.js'
import { useTitle } from '../useTitle.js'

export default function NotFound() {
  useTitle('Page not found')
  const { user } = useAuth()
  return (
    <div className="page">
      <h1>This road does not lead anywhere</h1>
      <p className="muted">The page you asked for does not exist. Check the address or go back to your start page.</p>
      <Link className="sign sign--go" to={homeFor(user)} style={{ width: 'fit-content' }}>
        Go to start page
      </Link>
    </div>
  )
}
