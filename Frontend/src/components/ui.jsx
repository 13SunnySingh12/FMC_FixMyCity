import { useEffect, useId, useState } from 'react'
import { CheckCircleIcon, WarningCircleIcon, WarningIcon } from '@phosphor-icons/react'
import { PRIORITY_LABEL, STATUS_LABEL, STATUS_ORDER, complaintNumber } from '../format.js'

/** The complaint's route from Submitted to Closed. The fill draws once when the page opens. */
export function Route({ status, counts }) {
  const index = STATUS_ORDER.indexOf(status)
  const [drawn, setDrawn] = useState(false)

  useEffect(() => {
    const frame = requestAnimationFrame(() => setDrawn(true))
    return () => cancelAnimationFrame(frame)
  }, [])

  if (counts) {
    return (
      <ol className="route route--counts" aria-label="Complaints at each stage">
        {STATUS_ORDER.map((stage) => (
          <li key={stage} className="route__stop">
            <span className="route__count">{counts[stage] ?? 0}</span>
            <span className="route__marker" aria-hidden="true" />
            <span className="route__label">{STATUS_LABEL[stage]}</span>
          </li>
        ))}
      </ol>
    )
  }

  return (
    <ol
      className="route"
      aria-label="Complaint progress"
      data-drawn={drawn}
      style={{ '--progress': Math.max(index, 0) / (STATUS_ORDER.length - 1) }}
    >
      {STATUS_ORDER.map((stage, i) => {
        const state = i < index ? 'is-passed' : i === index ? 'is-current' : ''
        return (
          <li key={stage} className={`route__stop ${state}`} aria-current={i === index ? 'step' : undefined}>
            <span className="route__marker" aria-hidden="true" />
            <span className="route__label">
              {STATUS_LABEL[stage]}
              {i < index && <span className="visually-hidden"> (done)</span>}
            </span>
          </li>
        )
      })}
    </ol>
  )
}

export function RouteMini({ status }) {
  const index = STATUS_ORDER.indexOf(status)
  return (
    <span className="row__status">
      <span className="route-mini" aria-hidden="true">
        {STATUS_ORDER.map((stage, i) => (
          <span key={stage} className={i < index ? 'on' : i === index ? 'now' : ''} />
        ))}
      </span>{' '}
      <span>{STATUS_LABEL[status]}</span>
    </span>
  )
}

export function NumberPlate({ id }) {
  return <span className="plate plate--number">{complaintNumber(id)}</span>
}

export function PriorityPlate({ priority }) {
  if (!priority) {
    return (
      <span className="plate plate--unset">
        <span className="visually-hidden">Priority: </span>Priority not set
      </span>
    )
  }
  return (
    <span className={`plate plate--${priority.toLowerCase()}`}>
      <span className="visually-hidden">Priority: </span>
      {PRIORITY_LABEL[priority]} priority
    </span>
  )
}

/** Label above, hint and error below; wires aria attributes onto the single child control. */
export function Field({ label, hint, error, optional, children, id: givenId }) {
  const generated = useId()
  const id = givenId ?? generated
  const hintId = hint ? `${id}-hint` : undefined
  const errorId = error ? `${id}-error` : undefined
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined
  return (
    <div className="field">
      <label htmlFor={id}>
        {label} {optional && <span className="field__optional">(optional)</span>}
      </label>
      {hint && (
        <p className="field__hint" id={hintId}>
          {hint}
        </p>
      )}
      {children({ id, 'aria-describedby': describedBy, 'aria-invalid': error ? true : undefined })}
      {error && (
        <p className="field__error" id={errorId}>
          {error}
        </p>
      )}
    </div>
  )
}

const NOTICE_ICONS = { error: WarningCircleIcon, ok: CheckCircleIcon, attention: WarningIcon }

export function Notice({ tone = 'info', children, live }) {
  const Icon = NOTICE_ICONS[tone]
  return (
    <div className={`notice notice--${tone}`} role={tone === 'error' ? 'alert' : live ? 'status' : undefined}>
      {Icon && <Icon size={20} aria-hidden="true" />}
      <div className="stack" style={{ gap: 6 }}>
        {children}
      </div>
    </div>
  )
}

export function ErrorNotice({ error }) {
  if (!error) return null
  return <Notice tone="error">{error.message}</Notice>
}

export function Empty({ title, children, action }) {
  return (
    <div className="empty">
      <h2>{title}</h2>
      {children && <p className="muted">{children}</p>}
      {action}
    </div>
  )
}

/** Placeholder rows shaped like the list they stand in for. */
export function RowsSkeleton({ count = 4 }) {
  return (
    <ul className="rows" aria-busy="true" aria-label="Loading">
      {Array.from({ length: count }, (_, i) => (
        <li key={i} className="row">
          <span className="skeleton" style={{ width: '38%', height: 16 }} />
          <span className="skeleton" style={{ width: '62%', height: 12 }} />
        </li>
      ))}
    </ul>
  )
}
