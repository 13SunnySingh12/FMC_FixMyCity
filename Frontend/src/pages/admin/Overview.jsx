import { Link } from 'react-router'
import { ErrorNotice, Notice, Route } from '../../components/ui.jsx'
import { useTitle } from '../../useTitle.js'
import { PRIORITY_LABEL } from '../../format.js'
import { useResource } from '../../useResource.js'

export default function Overview() {
  useTitle('Overview')
  const { data, error, loading } = useResource('/admin/analytics')

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Overview</h1>
          {data && (
            <p>
              {data.total} complaint{data.total === 1 ? '' : 's'} in total: {data.pending} open and {data.resolved} resolved
              or closed.
            </p>
          )}
        </div>
        <Link className="sign sign--go" to="/admin/complaints?status=SUBMITTED">
          Assign new complaints
        </Link>
      </div>

      <ErrorNotice error={error} />
      {loading && !data && <span className="skeleton" style={{ width: '100%', height: 80 }} aria-busy="true" />}

      {data && (
        <>
          {data.aiFailed > 0 && (
            <Notice tone="attention">
              <h2>
                {data.aiFailed} complaint{data.aiFailed === 1 ? ' needs' : 's need'} its AI analysis retried
              </h2>
              <p>
                Open the complaint and choose “Retry AI analysis”. Staff can still handle it without the analysis.
              </p>
            </Notice>
          )}

          <section className="section">
            <h2>Where complaints are on the route</h2>
            <Route counts={data.byStatus} />
          </section>

          <div className="split">
            <section className="section">
              <h2>By category</h2>
              <Bars rows={data.byCategory.map(({ name, count }) => [name, count])} />
            </section>
            <section className="section">
              <h2>By priority</h2>
              <Bars
                rows={Object.entries(data.byPriority).map(([priority, count]) => [PRIORITY_LABEL[priority] ?? 'Not set yet', count])}
              />
            </section>
          </div>

          <section className="section">
            <h2>By department</h2>
            <Bars rows={data.byDepartment.map(({ name, count }) => [name, count])} />
          </section>
        </>
      )}
    </div>
  )
}

/** Counts drawn against a visible scale; the widest bar is the largest count. */
function Bars({ rows }) {
  const max = Math.max(1, ...rows.map(([, count]) => count))
  return (
    <div className="bars">
      {rows.map(([label, count]) => (
        <div key={label} className="bar">
          <span>{label}</span>
          <span className="bar__track" aria-hidden="true">
            <span className="bar__fill" style={{ width: `${(count / max) * 100}%`, display: 'block' }} />
          </span>
          <span className="bar__value">{count}</span>
        </div>
      ))}
    </div>
  )
}
