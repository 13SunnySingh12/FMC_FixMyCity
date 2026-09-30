import { Link } from 'react-router'
import { NumberPlate, PriorityPlate, RouteMini } from './ui.jsx'
import { timeAgo } from '../format.js'

/** Complaint list rows. Staff rows add priority, the AI summary and the assigned officer. */
export function ComplaintRows({ items, staff, showOfficer }) {
  return (
    <ul className="rows">
      {items.map((c) => (
        <li key={c.id}>
          <Link className="row-link" to={`/complaints/${c.id}`}>
            <NumberPlate id={c.id} />
            <span className="row__body">
              <span className="row__title">{c.title}</span>
              {staff && c.aiSummary && <span className="row__summary">{c.aiSummary}</span>}
              <span className="row__meta">
                <RouteMini status={c.status} />
                {staff && <PriorityPlate priority={c.priority} />}
                <span>{c.categoryName}</span>
                <span>{c.location}</span>
                {showOfficer && <span>{c.assignedOfficerName ?? 'Not assigned'}</span>}
                <span>Updated {timeAgo(c.updatedAt)}</span>
              </span>
            </span>
          </Link>
        </li>
      ))}
    </ul>
  )
}

export function Pager({ data, onPage }) {
  if (!data || data.totalPages <= 1) return null
  const first = data.page * data.size + 1
  const last = Math.min(first + data.items.length - 1, data.totalItems)
  return (
    <nav className="pager" aria-label="Pages">
      <span className="muted small">
        Showing {first} to {last} of {data.totalItems}
      </span>
      <span className="row-actions">
        <button type="button" className="sign sign--plain sign--small" disabled={data.page === 0} onClick={() => onPage(data.page - 1)}>
          Previous
        </button>
        <button
          type="button"
          className="sign sign--plain sign--small"
          disabled={data.page + 1 >= data.totalPages}
          onClick={() => onPage(data.page + 1)}
        >
          Next
        </button>
      </span>
    </nav>
  )
}

export function StatusFilter({ value, onChange, options, legend = 'Show' }) {
  return (
    <fieldset>
      <legend className="visually-hidden">{legend}</legend>
      <div className="segments">
        {options.map(([optionValue, label]) => (
          <label key={optionValue || 'all'} className="segment">
            <input
              type="radio"
              name={legend}
              value={optionValue}
              checked={value === optionValue}
              onChange={() => onChange(optionValue)}
            />
            <span>{label}</span>
          </label>
        ))}
      </div>
    </fieldset>
  )
}
