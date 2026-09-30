import { useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router'
import { homeFor, useAuth } from '../authContext.js'
import { ErrorNotice, Field } from '../components/ui.jsx'
import { useTitle } from '../useTitle.js'

export default function Register() {
  useTitle('Create an account')
  const { user, register } = useAuth()
  const navigate = useNavigate()
  const [form, setForm] = useState({ name: '', email: '', phone: '', password: '' })
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  if (user) {
    return <Navigate to={homeFor(user)} replace />
  }

  const set = (key) => (event) => setForm((current) => ({ ...current, [key]: event.target.value }))
  const fieldError = (key) => error?.fieldErrors?.[key] && `This ${key === 'phone' ? 'phone number' : key} ${error.fieldErrors[key]}.`

  async function submit(event) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const registered = await register({ ...form, name: form.name.trim(), email: form.email.trim() })
      navigate(homeFor(registered), { replace: true })
    } catch (err) {
      setError(err)
      setBusy(false)
    }
  }

  return (
    <div className="page narrow">
      <div className="page-head">
        <div>
          <h1>Create an account</h1>
          <p>Report civic problems and follow them to the fix.</p>
        </div>
      </div>
      <form className="form panel" onSubmit={submit}>
        {error && !Object.keys(error.fieldErrors ?? {}).length && <ErrorNotice error={error} />}
        <Field label="Full name" error={fieldError('name')}>
          {(props) => (
            <input {...props} className="input" autoComplete="name" required maxLength={100} value={form.name} onChange={set('name')} />
          )}
        </Field>
        <Field label="Email" error={fieldError('email')}>
          {(props) => (
            <input
              {...props}
              className="input"
              type="email"
              autoComplete="email"
              required
              maxLength={254}
              value={form.email}
              onChange={set('email')}
            />
          )}
        </Field>
        <Field label="Phone" optional hint="Officers may call you about the problem." error={fieldError('phone')}>
          {(props) => (
            <input {...props} className="input" type="tel" autoComplete="tel" maxLength={20} value={form.phone} onChange={set('phone')} />
          )}
        </Field>
        <Field label="Password" hint="8 to 72 characters." error={fieldError('password')}>
          {(props) => (
            <input
              {...props}
              className="input"
              type="password"
              autoComplete="new-password"
              required
              minLength={8}
              maxLength={72}
              value={form.password}
              onChange={set('password')}
            />
          )}
        </Field>
        <button type="submit" className="sign sign--go sign--block" disabled={busy}>
          {busy ? 'Creating your account…' : 'Create account'}
        </button>
        <p className="small">
          Already registered? <Link to="/signin">Sign in</Link>
        </p>
      </form>
    </div>
  )
}
