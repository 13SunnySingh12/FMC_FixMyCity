import { useState } from 'react'
import { ComplaintRows, Pager, StatusFilter } from '../components/ComplaintRows.jsx'
import { Empty, ErrorNotice, Field, RowsSkeleton } from '../components/ui.jsx'
import { useTitle } from '../useTitle.js'
import { PRIORITY_LABEL, STATUS_LABEL } from '../format.js'
import { useResource } from '../useResource.js'
import { useAuth } from '../authContext.js'

const STATUSES = [
  ['ASSIGNED', 'Not started'],
  ['IN_PROGRESS', STATUS_LABEL.IN_PROGRESS],
  ['RESOLVED', 'Awaiting citizen'],
  ['CLOSED', STATUS_LABEL.CLOSED],
  ['', 'All'],
]

export default function WorkQueue() {
  useTitle('My queue')
  const { user } = useAuth()
  const [status, setStatus] = useState('ASSIGNED')
  const [priority, setPriority] = useState('')
  const [page, setPage] = useState(0)
  const { data, error, loading } = useResource('/complaints', { status, priority, page, size: 20 })

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>My queue</h1>
          <p>Complaints assigned to you in {user.departmentName ?? 'your department'}.</p>
        </div>
      </div>

      <div className="toolbar">
        <div className="field" style={{ gridColumn: '1 / -1' }}>
          <StatusFilter
            legend="Show complaints"
            value={status}
            options={STATUSES}
            onChange={(value) => {
              setStatus(value)
              setPage(0)
            }}
          />
        </div>
        <Field label="Priority">
          {(props) => (
            <select
              {...props}
              className="input"
              value={priority}
              onChange={(e) => {
                setPriority(e.target.value)
                setPage(0)
              }}
            >
              <option value="">Any priority</option>
              {Object.entries(PRIORITY_LABEL).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          )}
        </Field>
      </div>

      <ErrorNotice error={error} />
      {loading && !data ? (
        <RowsSkeleton />
      ) : data?.items.length ? (
        <>
          <ComplaintRows items={data.items} staff />
          <Pager data={data} onPage={setPage} />
        </>
      ) : (
        !error && (
          <Empty title={status === 'ASSIGNED' ? 'Nothing waiting to be started' : 'No complaints here'}>
            {status === 'ASSIGNED'
              ? 'New complaints appear here as soon as an administrator assigns them to you.'
              : 'Try another status or priority.'}
          </Empty>
        )
      )}
    </div>
  )
}
