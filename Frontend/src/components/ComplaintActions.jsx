import { useState } from 'react'
import { api } from '../api.js'
import { PRIORITY_LABEL } from '../format.js'
import { useResource } from '../useResource.js'
import { ErrorNotice, Field } from './ui.jsx'

const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp']

/**
 * The complaint's next step. The server lists what this user may do (`actions`); the most important one is the
 * single green sign, the rest are plain signs. Each opens its own inline form.
 */
export function ComplaintActions({ complaint, user, onDone, onStale }) {
  const [open, setOpen] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const can = (action) => complaint.actions.includes(action)
  const path = `/complaints/${complaint.id}`

  if (!complaint.actions.length) return null

  async function run(request, message) {
    setBusy(true)
    setError(null)
    try {
      const updated = await request()
      setOpen(null)
      onDone(updated, message)
    } catch (err) {
      setError(err)
      // A conflict means the complaint changed underneath us: show its current state with the message.
      if (err.status === 409) onStale?.()
    } finally {
      setBusy(false)
    }
  }

  const primary = primaryAction(complaint, can)
  const secondary = ['FEEDBACK', 'REOPEN', 'START', 'RESOLVE', 'UPLOAD_PROOF', 'ADD_NOTE', 'REASSIGN', 'ASSIGN', 'EDIT', 'CLOSE', 'RETRY_AI']
    .filter((action) => can(action) && action !== primary)

  const toggle = (action) => {
    setError(null)
    if (action === 'START') {
      run(() => api.post(`${path}/start`), 'Work started. The citizen can see the complaint is in progress.')
    } else if (action === 'RETRY_AI') {
      run(async () => {
        await api.post(`${path}/analysis/retry`)
        return api.get(path)
      }, 'AI analysis restarted. It runs in the background.')
    } else {
      setOpen((current) => (current === action ? null : action))
    }
  }

  const needsEvidence = user.role === 'OFFICER' && complaint.status === 'IN_PROGRESS' && !can('RESOLVE')

  return (
    <section className="next-step" aria-labelledby="next-step-heading">
      <h2 id="next-step-heading" className="visually-hidden">
        Next step
      </h2>
      {needsEvidence && (
        <p className="muted">
          To resolve, add an investigation note and upload at least one photo of the finished work for this round.
        </p>
      )}
      <div className="row-actions">
        {primary && (
          <button type="button" className="sign sign--go" onClick={() => toggle(primary)} disabled={busy} aria-expanded={isForm(primary) ? open === primary : undefined}>
            {labelFor(primary, complaint)}
          </button>
        )}
        {secondary.map((action) => (
          <button
            key={action}
            type="button"
            className="sign sign--plain sign--small"
            onClick={() => toggle(action)}
            disabled={busy}
            aria-expanded={isForm(action) ? open === action : undefined}
          >
            {labelFor(action, complaint)}
          </button>
        ))}
      </div>
      <ErrorNotice error={error} />
      {open === 'FEEDBACK' && <FeedbackForm busy={busy} onSubmit={(body) => run(() => api.post(`${path}/feedback`, body), 'Thank you. Your feedback closed the complaint.')} />}
      {open === 'REOPEN' && <ReopenForm busy={busy} onSubmit={(body) => run(() => api.post(`${path}/reopen`, body), 'Complaint reopened and sent back for more work.')} />}
      {open === 'ADD_NOTE' && <NoteForm busy={busy} onSubmit={(body) => run(() => api.post(`${path}/notes`, body), 'Note added.')} />}
      {open === 'UPLOAD_PROOF' && (
        <ProofForm
          busy={busy}
          remaining={complaint.proofsRemaining}
          onSubmit={(form) => run(() => api.upload(`${path}/proofs`, form), 'Resolution proof uploaded.')}
        />
      )}
      {open === 'RESOLVE' && <ResolveForm busy={busy} onSubmit={(body) => run(() => api.post(`${path}/resolve`, body), 'Marked resolved. The citizen has been asked to confirm the fix.')} />}
      {(open === 'ASSIGN' || open === 'REASSIGN') && (
        <AssignForm
          complaint={complaint}
          user={user}
          busy={busy}
          onSubmit={(body) =>
            run(
              () => api.post(`${path}/assignment`, body),
              user.role === 'OFFICER' ? 'Handed over. The complaint has left your queue.' : 'Assignment updated.',
            )
          }
        />
      )}
      {open === 'EDIT' && <EditForm complaint={complaint} busy={busy} onSubmit={(body) => run(() => api.patch(path, body), 'Complaint updated.')} />}
      {open === 'CLOSE' && <CloseForm busy={busy} onSubmit={(body) => run(() => api.post(`${path}/close`, body), 'Complaint closed.')} />}
    </section>
  )
}

const LABELS = {
  FEEDBACK: 'Confirm the fix',
  REOPEN: 'It is not fixed: reopen',
  START: 'Start work',
  RESOLVE: 'Mark resolved',
  UPLOAD_PROOF: 'Upload resolution proof',
  ADD_NOTE: 'Add investigation note',
  REASSIGN: 'Reassign',
  ASSIGN: 'Assign',
  EDIT: 'Edit category or priority',
  CLOSE: 'Close complaint',
  RETRY_AI: 'Retry AI analysis',
}

const isForm = (action) => action !== 'START' && action !== 'RETRY_AI'

// Assigning is the next step only while a complaint waits in SUBMITTED; afterwards it is a reassignment.
function primaryAction(complaint, can) {
  for (const action of ['FEEDBACK', 'START', 'RESOLVE', 'UPLOAD_PROOF', 'ASSIGN', 'CLOSE']) {
    if (can(action) && !(action === 'ASSIGN' && complaint.status !== 'SUBMITTED')) return action
  }
  return null
}

const labelFor = (action, complaint) => (action === 'ASSIGN' && complaint.status !== 'SUBMITTED' ? 'Reassign' : LABELS[action])

function FeedbackForm({ busy, onSubmit }) {
  const [rating, setRating] = useState(null)
  const [comment, setComment] = useState('')
  const [error, setError] = useState(null)
  return (
    <form
      className="inline-form"
      onSubmit={(event) => {
        event.preventDefault()
        if (!rating) return setError('Choose a rating from 1 to 5.')
        onSubmit({ rating, comment: comment.trim() || null })
      }}
    >
      <fieldset className="field" aria-describedby={error ? 'rating-error' : undefined}>
        <legend className="label">How well was the problem fixed?</legend>
        <div className="segments stars">
          {[1, 2, 3, 4, 5].map((value) => (
            <label key={value} className="segment">
              <input type="radio" name="rating" value={value} checked={rating === value} onChange={() => setRating(value)} />
              <span>
                {value}
                <span className="visually-hidden"> out of 5</span>
              </span>
            </label>
          ))}
        </div>
        <p className="field__hint">1 is poor, 5 is excellent. Giving feedback closes the complaint.</p>
        {error && (
          <p className="field__error" id="rating-error">
            {error}
          </p>
        )}
      </fieldset>
      <Field label="Comment" optional>
        {(props) => <textarea {...props} className="input" rows={3} maxLength={1000} value={comment} onChange={(e) => setComment(e.target.value)} />}
      </Field>
      <div className="row-actions">
        <button type="submit" className="sign sign--go sign--small" disabled={busy}>
          {busy ? 'Sending…' : 'Send feedback and close'}
        </button>
      </div>
    </form>
  )
}

function ReopenForm({ busy, onSubmit }) {
  const [reason, setReason] = useState('')
  const [error, setError] = useState(null)
  return (
    <form
      className="inline-form"
      onSubmit={(event) => {
        event.preventDefault()
        if (reason.trim().length < 10) return setError('Explain what is still wrong in at least 10 characters.')
        onSubmit({ reason: reason.trim() })
      }}
    >
      <Field label="What is still wrong?" hint="The complaint goes back to the officer who worked on it." error={error}>
        {(props) => <textarea {...props} className="input" rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}
      </Field>
      <div className="row-actions">
        <button type="submit" className="sign sign--go sign--small" disabled={busy}>
          {busy ? 'Reopening…' : 'Reopen complaint'}
        </button>
      </div>
    </form>
  )
}

function NoteForm({ busy, onSubmit }) {
  const [body, setBody] = useState('')
  const [error, setError] = useState(null)
  return (
    <form
      className="inline-form"
      onSubmit={(event) => {
        event.preventDefault()
        if (!body.trim()) return setError('Write what you found or did.')
        onSubmit({ body: body.trim() })
      }}
    >
      <Field label="Investigation note" hint="What you found on site and what was done. The citizen can read it." error={error}>
        {(props) => <textarea {...props} className="input" rows={4} maxLength={2000} value={body} onChange={(e) => setBody(e.target.value)} />}
      </Field>
      <div className="row-actions">
        <button type="submit" className="sign sign--go sign--small" disabled={busy}>
          {busy ? 'Saving…' : 'Add note'}
        </button>
      </div>
    </form>
  )
}

function ProofForm({ busy, remaining, onSubmit }) {
  const [files, setFiles] = useState([])
  const [error, setError] = useState(null)
  return (
    <form
      className="inline-form"
      onSubmit={(event) => {
        event.preventDefault()
        if (!files.length) return setError('Choose at least one photo.')
        const form = new FormData()
        files.forEach((file) => form.append('images', file))
        onSubmit(form)
      }}
    >
      <Field label="Photos of the finished work" hint={`JPEG, PNG or WebP, up to 5 MB each. You can add ${remaining} more.`} error={error}>
        {(props) => (
          <input
            {...props}
            className="input"
            type="file"
            multiple
            accept={IMAGE_TYPES.join(',')}
            onChange={(event) => {
              const chosen = [...event.target.files]
              // A rejected choice clears the selection, so what uploads is always what the picker shows.
              const reject = (message) => {
                event.target.value = ''
                setFiles([])
                setError(message)
              }
              if (chosen.length > remaining) return reject(`You can add ${remaining} more photo${remaining === 1 ? '' : 's'}.`)
              const bad = chosen.find((file) => !IMAGE_TYPES.includes(file.type) || file.size > 5 * 1024 * 1024)
              if (bad) return reject(`${bad.name} is not a JPEG, PNG or WebP photo of 5 MB or less.`)
              setError(null)
              setFiles(chosen)
            }}
          />
        )}
      </Field>
      <div className="row-actions">
        <button type="submit" className="sign sign--go sign--small" disabled={busy}>
          {busy ? 'Uploading…' : `Upload ${files.length || ''} photo${files.length === 1 ? '' : 's'}`}
        </button>
      </div>
    </form>
  )
}

function ResolveForm({ busy, onSubmit }) {
  const [note, setNote] = useState('')
  return (
    <form
      className="inline-form"
      onSubmit={(event) => {
        event.preventDefault()
        onSubmit({ note: note.trim() || null })
      }}
    >
      <Field label="Resolution summary" optional hint="Shown on the route log, for example “Pothole filled and levelled”.">
        {(props) => <input {...props} className="input" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />}
      </Field>
      <div className="row-actions">
        <button type="submit" className="sign sign--go sign--small" disabled={busy}>
          {busy ? 'Resolving…' : 'Confirm resolved'}
        </button>
      </div>
    </form>
  )
}

function AssignForm({ complaint, user, busy, onSubmit }) {
  const departments = useResource('/departments')
  const [departmentId, setDepartmentId] = useState(String(complaint.ai?.departmentId ?? complaint.departmentId ?? ''))
  const officers = useResource(departmentId ? '/officers' : null, { departmentId })
  const [officerId, setOfficerId] = useState('')
  const [note, setNote] = useState('')
  const [error, setError] = useState(null)
  const choices = (officers.data ?? []).filter((officer) => officer.id !== user.id && officer.id !== complaint.assignedOfficer?.id)
  // Officers who share a name are told apart by their work email.
  const namesake = (officer) => choices.some((other) => other.id !== officer.id && other.name === officer.name)
  const noOfficers = departmentId && officers.data && choices.length === 0
  const officerHint = noOfficers
    ? `No other active officer works in this department yet. Send it to the department's queue${user.role === 'ADMIN' ? ', or add an officer under People first' : ''}.`
    : "Leave the department's queue to assign it later, or choose an active officer now."

  return (
    <form
      className="inline-form"
      onSubmit={(event) => {
        event.preventDefault()
        if (!departmentId) return setError('Choose a department.')
        onSubmit(officerId ? { officerId: Number(officerId), note: note.trim() || null } : { departmentId: Number(departmentId), note: note.trim() || null })
      }}
    >
      <ErrorNotice error={departments.error || officers.error} />
      <Field label="Department" error={error}>
        {(props) => (
          <select
            {...props}
            className="input"
            value={departmentId}
            onChange={(e) => {
              setDepartmentId(e.target.value)
              setOfficerId('')
            }}
          >
            <option value="">Choose a department</option>
            {(departments.data ?? []).map((department) => (
              <option key={department.id} value={department.id}>
                {department.name}
                {department.id === complaint.ai?.departmentId ? ' (AI suggestion)' : ''}
              </option>
            ))}
          </select>
        )}
      </Field>
      <Field label="Officer" hint={officerHint}>
        {(props) => (
          <select {...props} className="input" value={officerId} onChange={(e) => setOfficerId(e.target.value)} disabled={!departmentId}>
            <option value="">No officer yet (department queue)</option>
            {choices.map((officer) => (
              <option key={officer.id} value={officer.id}>
                {officer.name}
                {namesake(officer) && officer.email ? ` (${officer.email})` : ''}
              </option>
            ))}
          </select>
        )}
      </Field>
      <Field label="Reason" optional>
        {(props) => <input {...props} className="input" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />}
      </Field>
      <div className="row-actions">
        <button type="submit" className="sign sign--go sign--small" disabled={busy}>
          {busy ? 'Saving…' : officerId ? 'Assign to officer' : 'Send to department queue'}
        </button>
      </div>
    </form>
  )
}

function EditForm({ complaint, busy, onSubmit }) {
  const categories = useResource('/categories')
  const [categoryId, setCategoryId] = useState(String(complaint.categoryId))
  const [priority, setPriority] = useState(complaint.priority ?? '')
  return (
    <form
      className="inline-form"
      onSubmit={(event) => {
        event.preventDefault()
        onSubmit({ categoryId: Number(categoryId), priority: priority || null })
      }}
    >
      <ErrorNotice error={categories.error} />
      <Field label="Category">
        {(props) => (
          <select {...props} className="input" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
            {(categories.data ?? []).map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
          </select>
        )}
      </Field>
      <Field label="Priority">
        {(props) => (
          <select {...props} className="input" value={priority} onChange={(e) => setPriority(e.target.value)}>
            <option value="">Keep as it is</option>
            {Object.entries(PRIORITY_LABEL).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        )}
      </Field>
      <div className="row-actions">
        <button type="submit" className="sign sign--go sign--small" disabled={busy}>
          {busy ? 'Saving…' : 'Save changes'}
        </button>
      </div>
    </form>
  )
}

function CloseForm({ busy, onSubmit }) {
  const [note, setNote] = useState('')
  return (
    <form
      className="inline-form"
      onSubmit={(event) => {
        event.preventDefault()
        onSubmit({ note: note.trim() || null })
      }}
    >
      <Field label="Reason for closing" optional hint="For example, the citizen did not respond after the fix.">
        {(props) => <input {...props} className="input" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />}
      </Field>
      <div className="row-actions">
        <button type="submit" className="sign sign--go sign--small" disabled={busy}>
          {busy ? 'Closing…' : 'Close complaint'}
        </button>
      </div>
    </form>
  )
}
