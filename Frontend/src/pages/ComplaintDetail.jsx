import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'
import { ArrowLeftIcon, ClockIcon, MapPinIcon, TagIcon, UserIcon } from '@phosphor-icons/react'
import { listFor, useAuth } from '../authContext.js'
import { ComplaintActions } from '../components/ComplaintActions.jsx'
import { ErrorNotice, Notice, NumberPlate, PriorityPlate, Route } from '../components/ui.jsx'
import { useTitle } from '../useTitle.js'
import { ROLE_LABEL, STATUS_LABEL, complaintNumber, formatDateTime, timeAgo } from '../format.js'
import { useResource } from '../useResource.js'

export default function ComplaintDetail() {
  const { id } = useParams()
  const { user } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const notice = useRef(null)
  const focusNotice = useRef(false)
  // Complaint ids are numbers; anything else in the address is not a complaint and is never sent to the server.
  const known = /^\d+$/.test(id)
  const { data: complaint, error, loading, reload, setData } = useResource(known ? `/complaints/${id}` : null)
  const [message, setMessage] = useState(
    location.state?.justSubmitted ? 'Complaint submitted. It will be analysed and assigned to the right department.' : null,
  )
  useTitle(complaint ? `${complaintNumber(complaint.id)} ${complaint.title}` : 'Complaint')

  // The "submitted" confirmation belongs to that one arrival; drop it from history so a reload does not repeat it.
  useEffect(() => {
    if (location.state?.justSubmitted) navigate(location.pathname, { replace: true, state: null })
  }, [location, navigate])

  // AI analysis runs on the server; poll while it works: often at first, then every 30 seconds while retries run.
  // An outage does not end the polling; a complaint that is no longer visible (handed to someone else) does.
  const gone = error?.status >= 400 && error?.status < 500
  const aiRunning = complaint && !gone && ['PENDING', 'PROCESSING'].includes(complaint.ai.status)
  const polls = useRef(0)
  useEffect(() => {
    if (!aiRunning) {
      polls.current = 0
      return
    }
    const timer = setTimeout(
      () => {
        polls.current += 1
        reload()
      },
      polls.current < 15 ? 4000 : 30000,
    )
    return () => clearTimeout(timer)
  }, [aiRunning, complaint, error, reload])

  // After an action the finished form unmounts; move focus to its result instead of dropping it on the page.
  useEffect(() => {
    if (!focusNotice.current) return
    focusNotice.current = false
    notice.current?.focus()
  }, [message, complaint])

  const [backTo, backLabel] = listFor(user)
  const back = (
    <Link className="back" to={backTo}>
      <ArrowLeftIcon aria-hidden="true" /> {backLabel}
    </Link>
  )

  if (!known || (error && !complaint)) {
    const missing = !known || [400, 404].includes(error.status)
    return (
      <div className="page">
        {back}
        <ErrorNotice error={missing ? { message: 'This complaint does not exist or is not visible to you.' } : error} />
      </div>
    )
  }
  if (loading && !complaint) {
    return (
      <div className="page" aria-busy="true">
        {back}
        <span className="skeleton" style={{ width: 120, height: 26 }} />
        <span className="skeleton" style={{ width: '70%', height: 34 }} />
        <span className="skeleton" style={{ width: '100%', height: 48 }} />
      </div>
    )
  }

  const staff = user.role !== 'CITIZEN'
  const c = complaint

  return (
    <article className="page">
      {back}

      <header className="complaint-head">
        <div className="row-actions">
          <NumberPlate id={c.id} />
          <PriorityPlate priority={c.priority} />
        </div>
        <h1>{c.title}</h1>
        <p className="complaint-head__meta">
          <span>
            <TagIcon aria-hidden="true" /> {c.categoryName}
          </span>
          <span>
            <MapPinIcon aria-hidden="true" /> {c.location}
          </span>
          <span>
            <ClockIcon aria-hidden="true" /> Reported <span className="mono">{formatDateTime(c.createdAt)}</span>
          </span>
        </p>
      </header>

      <section aria-label="Progress">
        <Route status={c.status} />
        <p className="holder">
          <UserIcon aria-hidden="true" /> {holder(c)}
        </p>
      </section>

      {/* The result sits next to the action that caused it. */}
      {message && (
        <div ref={notice} tabIndex={-1}>
          <Notice tone="ok" live>
            {message}
          </Notice>
        </div>
      )}

      <ComplaintActions
        complaint={c}
        user={user}
        onStale={reload}
        onDone={(updated, text) => {
          setData(updated)
          setMessage(text)
          focusNotice.current = true
        }}
      />

      <div className="split">
        <div className="stack-lg">
          <section className="section">
            <h2>Description</h2>
            <p className="description">{c.description}</p>
          </section>

          {c.images.length > 0 && (
            <section className="section">
              <h2>Photo</h2>
              <Gallery images={c.images} alt="Photo of the problem, submitted with the complaint" onExpired={reload} />
            </section>
          )}

          <section className="section">
            <h2>Investigation notes</h2>
            {c.notes.length ? (
              <ul className="rows">
                {c.notes.map((note, i) => (
                  <li key={i} className="row">
                    <p>{note.body}</p>
                    <p className="small muted">
                      {note.author}, <span className="mono">{formatDateTime(note.at)}</span>
                    </p>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="muted">No investigation notes yet.</p>
            )}
          </section>

          <section className="section">
            <h2>Resolution proof</h2>
            {c.proofs.length ? (
              <Gallery images={c.proofs} alt="Photo of the finished work, uploaded by the officer" onExpired={reload} />
            ) : (
              <p className="muted">No proof uploaded yet. Officers add photos of the finished work before resolving.</p>
            )}
          </section>

          <section className="section">
            <h2>Route log</h2>
            <ol className="log">
              {c.timeline.map((entry, i) => (
                <li key={i}>
                  <span className="log__dot" aria-hidden="true" />
                  <div className="stack" style={{ gap: 4 }}>
                    <div className="log__head">
                      <strong>{STATUS_LABEL[entry.status]}</strong>
                      <span className="mono muted">{formatDateTime(entry.at)}</span>
                    </div>
                    {entry.note && <p>{entry.note}</p>}
                    <p className="log__who">
                      {entry.actorName}, {ROLE_LABEL[entry.actorRole]}
                    </p>
                  </div>
                </li>
              ))}
            </ol>
          </section>
        </div>

        <aside className="stack-lg" aria-label="Complaint details">
          <div className="panel">
            <h2>Details</h2>
            <dl className="facts">
              <div>
                <dt>Status</dt>
                <dd>{STATUS_LABEL[c.status]}</dd>
              </div>
              <div>
                <dt>Department</dt>
                <dd>{c.departmentName ?? 'Not routed yet'}</dd>
              </div>
              <div>
                <dt>Officer</dt>
                <dd>{c.assignedOfficer?.name ?? 'Not assigned yet'}</dd>
              </div>
              {staff && (
                <div>
                  <dt>Reported by</dt>
                  <dd>
                    {c.citizen.name}
                    {c.citizen.phone && (
                      <>
                        <br />
                        <a href={`tel:${c.citizen.phone}`}>{c.citizen.phone}</a>
                      </>
                    )}
                  </dd>
                </div>
              )}
              <div>
                <dt>Last update</dt>
                <dd className="mono">
                  <time dateTime={c.updatedAt} title={formatDateTime(c.updatedAt)}>
                    {timeAgo(c.updatedAt)}
                  </time>
                </dd>
              </div>
            </dl>
          </div>

          <AiPanel complaint={c} staff={staff} isAdmin={user.role === 'ADMIN'} />

          {c.feedback && (
            <div className="panel">
              <h2>Citizen feedback</h2>
              <p>
                <strong>{c.feedback.rating} out of 5</strong>
              </p>
              {c.feedback.comment && <p>{c.feedback.comment}</p>}
              <p className="small muted mono">{formatDateTime(c.feedback.at)}</p>
            </div>
          )}
        </aside>
      </div>
    </article>
  )
}

/** Who holds the complaint right now, in the words of the route. */
function holder(c) {
  if (!c.assignedOfficer) {
    return c.departmentName ? `Waiting for an officer in ${c.departmentName}` : 'Waiting to be routed to a department'
  }
  const verb = { RESOLVED: 'Resolved by', CLOSED: 'Handled by' }[c.status] ?? 'With'
  return `${verb} ${c.assignedOfficer.name}, ${c.departmentName}`
}

function AiPanel({ complaint, staff, isAdmin }) {
  const ai = complaint.ai
  if (ai.status === 'PENDING' || ai.status === 'PROCESSING') {
    return (
      <div className="panel" aria-live="polite">
        <h2>AI analysis</h2>
        <p>
          Reading the description{complaint.images.length ? ' and photo' : ''}. This runs in the background, so you can
          leave this page and come back.
        </p>
      </div>
    )
  }
  if (ai.status === 'FAILED') {
    return (
      <Notice tone="attention">
        <h2>AI analysis could not be completed</h2>
        <p>The complaint is still handled normally by staff.{isAdmin && ai.error ? ` Reason: ${ai.error}` : ''}</p>
      </Notice>
    )
  }
  const categoryDiffers = ai.categoryId && ai.categoryId !== complaint.categoryId
  return (
    <div className="panel">
      <h2>AI analysis</h2>
      <p className="small muted">Suggestions to help staff route the complaint. The citizen's own choices are kept.</p>
      <dl className="facts">
        <div>
          <dt>Suggested category</dt>
          <dd>
            {ai.categoryName ?? 'No longer available'}
            {categoryDiffers && <span className="tag"> (reported as {complaint.categoryName})</span>}
          </dd>
        </div>
        <div>
          <dt>Suggested priority</dt>
          <dd>
            <PriorityPlate priority={ai.priority} />
          </dd>
        </div>
        <div>
          <dt>Suggested department</dt>
          <dd>{ai.departmentName ?? 'No longer available'}</dd>
        </div>
      </dl>
      <div className="section">
        <h3>Summary</h3>
        <p>{ai.summary}</p>
      </div>
      {ai.imageFindings && (
        <div className="section">
          <h3>What the photo shows</h3>
          <p>{ai.imageFindings}</p>
        </div>
      )}
      <p className="small muted">
        Analysed <span className="mono">{formatDateTime(ai.updatedAt)}</span>
        {staff && ai.model ? `, ${ai.model}` : ''}
      </p>
    </div>
  )
}

/** Signed photo links expire after about ten minutes; offer a refresh instead of broken images. */
function Gallery({ images, alt, onExpired }) {
  const [expired, setExpired] = useState(false)
  if (expired) {
    return (
      <Notice>
        <p>Photo links expire after about ten minutes for privacy.</p>
        <button
          type="button"
          className="sign sign--plain sign--small"
          style={{ width: 'fit-content' }}
          onClick={() => {
            setExpired(false)
            onExpired()
          }}
        >
          Show photos again
        </button>
      </Notice>
    )
  }
  return (
    <div className="gallery">
      {images.map((image, i) => (
        <a key={image.id} href={image.url} target="_blank" rel="noreferrer">
          <img src={image.url} alt={images.length > 1 ? `${alt} (${i + 1} of ${images.length})` : alt} loading="lazy" onError={() => setExpired(true)} />
        </a>
      ))}
    </div>
  )
}
