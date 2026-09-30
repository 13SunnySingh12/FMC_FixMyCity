import { useState } from 'react'
import { Link } from 'react-router'
import { PlusIcon } from '@phosphor-icons/react'
import { ComplaintRows, Pager, StatusFilter } from '../components/ComplaintRows.jsx'
import { Empty, ErrorNotice, RowsSkeleton } from '../components/ui.jsx'
import { useTitle } from '../useTitle.js'
import { STATUS_LABEL, STATUS_ORDER } from '../format.js'
import { useResource } from '../useResource.js'

const FILTERS = [['', 'All'], ...STATUS_ORDER.map((status) => [status, STATUS_LABEL[status]])]

export default function MyComplaints() {
  useTitle('My complaints')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const { data, error, loading } = useResource('/complaints', { status, page, size: 20 })

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>My complaints</h1>
          <p>Everything you have reported and where it is now.</p>
        </div>
        <Link className="sign sign--go" to="/complaints/new">
          <PlusIcon aria-hidden="true" /> Report a problem
        </Link>
      </div>

      <StatusFilter
        legend="Show complaints"
        value={status}
        options={FILTERS}
        onChange={(value) => {
          setStatus(value)
          setPage(0)
        }}
      />

      <ErrorNotice error={error} />
      {loading && !data ? (
        <RowsSkeleton />
      ) : data?.items.length ? (
        <>
          <ComplaintRows items={data.items} />
          <Pager data={data} onPage={setPage} />
        </>
      ) : status ? (
        <Empty title={`Nothing ${STATUS_LABEL[status].toLowerCase()}`}>No complaint of yours is at this stage right now.</Empty>
      ) : (
        !error && (
          <Empty
            title="You haven't reported anything yet"
            action={
              <Link className="sign sign--go" to="/complaints/new">
                Report a problem
              </Link>
            }
          >
            When you report a civic problem, you can follow it here from submission to the fix.
          </Empty>
        )
      )}
    </div>
  )
}
