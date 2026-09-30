export const STATUS_ORDER = ['SUBMITTED', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED']

export const STATUS_LABEL = {
  SUBMITTED: 'Submitted',
  ASSIGNED: 'Assigned',
  IN_PROGRESS: 'In progress',
  RESOLVED: 'Resolved',
  CLOSED: 'Closed',
}

export const PRIORITY_LABEL = { HIGH: 'High', MEDIUM: 'Medium', LOW: 'Low' }

export const ROLE_LABEL = { CITIZEN: 'Citizen', OFFICER: 'Officer', ADMIN: 'Administrator' }

export function complaintNumber(id) {
  return `FMC-${String(id).padStart(5, '0')}`
}

const dateTime = new Intl.DateTimeFormat(undefined, {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
})

const dateOnly = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', year: 'numeric' })

export function formatDateTime(iso) {
  return iso ? dateTime.format(new Date(iso)) : ''
}

export function formatDate(iso) {
  return iso ? dateOnly.format(new Date(iso)) : ''
}

const relative = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })

/** "3 hours ago", "yesterday", falling back to the date after a month. */
export function timeAgo(iso) {
  const seconds = (new Date(iso).getTime() - Date.now()) / 1000
  const steps = [
    ['minute', 60],
    ['hour', 3600],
    ['day', 86400],
  ]
  if (Math.abs(seconds) < 60) return 'just now'
  if (Math.abs(seconds) > 30 * 86400) return formatDate(iso)
  let unit = 'minute'
  let size = 60
  for (const [name, span] of steps) {
    if (Math.abs(seconds) >= span) {
      unit = name
      size = span
    }
  }
  return relative.format(Math.round(seconds / size), unit)
}
