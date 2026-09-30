import { useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router'
import { homeFor, useAuth } from '../authContext.js'
import { ErrorNotice, Field, Notice } from '../components/ui.jsx'
import { useTitle } from '../useTitle.js'

export default function SignIn() {
  useTitle('Sign in')
  const { user, login, sessionEnded } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const returnTo = location.state?.from?.pathname

  if (user) {
    return <Navigate to={returnTo ?? homeFor(user)} replace />
  }

  async function submit(event) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const signedIn = await login(email.trim(), password)
      navigate(returnTo ?? homeFor(signedIn), { replace: true })
    } catch (err) {
      setError(err)
      setBusy(false)
    }
  }

  return (
    <div className="page narrow">
      <div className="page-head">
        <div>
          <h1>Sign in</h1>
          <p>Citizens, officers and administrators all sign in here.</p>
        </div>
      </div>
      <form className="form panel" onSubmit={submit}>
        {sessionEnded && !error && <Notice live>Your session has ended. Please sign in again.</Notice>}
        <ErrorNotice error={error} />
        <Field label="Email">
          {(props) => (
            <input
              {...props}
              className="input"
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          )}
        </Field>
        <Field label="Password">
          {(props) => (
            <input
              {...props}
              className="input"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          )}
        </Field>
        <button type="submit" className="sign sign--go sign--block" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
        <p className="small">
          New to FixMyCity? <Link to="/register">Create an account</Link>
        </p>
      </form>
    </div>
  )
}
