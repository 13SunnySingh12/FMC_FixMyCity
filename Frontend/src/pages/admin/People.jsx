import { useState } from 'react'
import { PlusIcon } from '@phosphor-icons/react'
import { api } from '../../api.js'
import { Pager, StatusFilter } from '../../components/ComplaintRows.jsx'
import { Empty, ErrorNotice, Field, Notice, RowsSkeleton } from '../../components/ui.jsx'
import { useTitle } from '../../useTitle.js'
import { formatDate } from '../../format.js'
import { useResource } from '../../useResource.js'

const TABS = [
  ['CITIZEN', 'Citizens'],
  ['OFFICER', 'Officers'],
]

export default function People() {
  useTitle('People')
  const [role, setRole] = useState('CITIZEN')
  const [page, setPage] = useState(0)
  const [adding, setAdding] = useState(false)
  const [message, setMessage] = useState(null)
  const { data, error, loading, reload } = useResource('/admin/users', { role, page, size: 20 })
  const departments = useResource('/departments')

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>People</h1>
          <p>Citizen accounts and the officers who work on complaints.</p>
        </div>
        {role === 'OFFICER' && (
          <button
            type="button"
            className="sign sign--go"
            aria-expanded={adding}
            onClick={() => {
              setMessage(null)
              setAdding((open) => !open)
            }}
          >
            <PlusIcon aria-hidden="true" /> Add an officer
          </button>
        )}
      </div>

      <StatusFilter
        legend="Show"
        value={role}
        options={TABS}
        onChange={(value) => {
          setRole(value)
          setPage(0)
          setAdding(false)
          setMessage(null)
        }}
      />

      {message && (
        <Notice tone="ok" live>
          {message}
        </Notice>
      )}
      {adding && (
        <AddOfficer
          departments={departments.data ?? []}
          onAdded={(officer) => {
            setAdding(false)
            setMessage(`${officer.name} can now sign in as an officer in ${officer.departmentName}.`)
            reload()
          }}
        />
      )}

      <ErrorNotice error={error} />
      {loading && !data ? (
        <RowsSkeleton />
      ) : data?.items.length ? (
        <>
          <ul className="rows">
            {data.items.map((person) => (
              <PersonRow key={person.id} person={person} departments={departments.data ?? []} onChanged={reload} />
            ))}
          </ul>
          <Pager data={data} onPage={setPage} />
        </>
      ) : (
        !error && (
          <Empty title={role === 'OFFICER' ? 'No officers yet' : 'No citizens yet'}>
            {role === 'OFFICER' ? 'Add officers so complaints can be assigned to them.' : 'Citizens appear here when they register.'}
          </Empty>
        )
      )}
    </div>
  )
}

function PersonRow({ person, departments, onChanged }) {
  const [confirming, setConfirming] = useState(false)
  const [departmentId, setDepartmentId] = useState(String(person.departmentId ?? ''))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  async function change(body) {
    setBusy(true)
    setError(null)
    try {
      await api.patch(`/admin/users/${person.id}`, body)
      setConfirming(false)
      onChanged()
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <li className="manage-row">
      <div className="stack" style={{ gap: 2 }}>
        <span className="manage-row__title">
          {person.name} {!person.active && <span className="tag">(deactivated)</span>}
        </span>
        <span className="small muted">
          {person.email}
          {person.phone ? `, ${person.phone}` : ''}
        </span>
        <span className="small muted">
          {person.role === 'OFFICER' ? `${person.departmentName}, ` : ''}joined {formatDate(person.createdAt)}
        </span>
        <ErrorNotice error={error} />
      </div>
      <div className="row-actions">
        {person.role === 'OFFICER' && person.active && (
          <form
            className="row-actions"
            onSubmit={(event) => {
              event.preventDefault()
              change({ departmentId: Number(departmentId) })
            }}
          >
            <label className="visually-hidden" htmlFor={`dept-${person.id}`}>
              Department for {person.name}
            </label>
            <select id={`dept-${person.id}`} className="input" style={{ width: 'auto' }} value={departmentId} onChange={(e) => setDepartmentId(e.target.value)}>
              {departments.map((department) => (
                <option key={department.id} value={department.id}>
                  {department.name}
                </option>
              ))}
            </select>
            {departmentId !== String(person.departmentId) && (
              <button type="submit" className="sign sign--plain sign--small" disabled={busy}>
                Move
              </button>
            )}
          </form>
        )}
        {person.active ? (
          confirming ? (
            <>
              <span className="small">They will be signed out immediately.</span>
              <button type="button" className="sign sign--stop sign--small" disabled={busy} onClick={() => change({ active: false })}>
                Deactivate {person.name.split(' ')[0]}
              </button>
              <button
                type="button"
                className="sign sign--quiet sign--small"
                onClick={() => {
                  setError(null)
                  setConfirming(false)
                }}
              >
                Cancel
              </button>
            </>
          ) : (
            <button type="button" className="sign sign--plain sign--small" onClick={() => setConfirming(true)}>
              Deactivate
            </button>
          )
        ) : (
          <button type="button" className="sign sign--plain sign--small" disabled={busy} onClick={() => change({ active: true })}>
            Reactivate
          </button>
        )}
      </div>
    </li>
  )
}

function AddOfficer({ departments, onAdded }) {
  const [form, setForm] = useState({ name: '', email: '', phone: '', password: '', departmentId: '' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const set = (key) => (event) => setForm((current) => ({ ...current, [key]: event.target.value }))
  const fieldError = (key) => error?.fieldErrors?.[key] && `This ${key === 'departmentId' ? 'department' : key} ${error.fieldErrors[key]}.`

  async function submit(event) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const officer = await api.post('/admin/officers', {
        ...form,
        name: form.name.trim(),
        email: form.email.trim(),
        departmentId: form.departmentId ? Number(form.departmentId) : null,
      })
      onAdded(officer)
    } catch (err) {
      setError(err)
      setBusy(false)
    }
  }

  return (
    <form className="inline-form" onSubmit={submit} style={{ maxWidth: '40rem' }}>
      <h2>Add an officer</h2>
      {error && !Object.keys(error.fieldErrors ?? {}).length && <ErrorNotice error={error} />}
      <Field label="Full name" error={fieldError('name')}>
        {(props) => <input {...props} className="input" required maxLength={100} value={form.name} onChange={set('name')} />}
      </Field>
      <Field label="Work email" error={fieldError('email')}>
        {(props) => <input {...props} className="input" type="email" required maxLength={254} value={form.email} onChange={set('email')} />}
      </Field>
      <Field label="Phone" optional error={fieldError('phone')}>
        {(props) => <input {...props} className="input" type="tel" maxLength={20} value={form.phone} onChange={set('phone')} />}
      </Field>
      <Field label="Department" error={fieldError('departmentId')}>
        {(props) => (
          <select {...props} className="input" required value={form.departmentId} onChange={set('departmentId')}>
            <option value="">Choose a department</option>
            {departments.map((department) => (
              <option key={department.id} value={department.id}>
                {department.name}
              </option>
            ))}
          </select>
        )}
      </Field>
      <Field label="Temporary password" hint="8 to 72 characters. Share it with the officer securely." error={fieldError('password')}>
        {(props) => (
          <input {...props} className="input" type="password" autoComplete="new-password" required minLength={8} maxLength={72} value={form.password} onChange={set('password')} />
        )}
      </Field>
      <div className="row-actions">
        <button type="submit" className="sign sign--go sign--small" disabled={busy}>
          {busy ? 'Adding…' : 'Add officer'}
        </button>
      </div>
    </form>
  )
}
