import { useSearchParams } from 'react-router'
import { ComplaintRows, Pager } from '../../components/ComplaintRows.jsx'
import { Empty, ErrorNotice, Field, RowsSkeleton } from '../../components/ui.jsx'
import { useTitle } from '../../useTitle.js'
import { PRIORITY_LABEL, STATUS_LABEL, STATUS_ORDER } from '../../format.js'
import { useResource } from '../../useResource.js'

const FILTERS = ['status', 'categoryId', 'departmentId', 'priority']

export default function AllComplaints() {
  useTitle('All complaints')
  const [params, setParams] = useSearchParams()
  const filters = Object.fromEntries(FILTERS.map((key) => [key, params.get(key) ?? '']))
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '', 10) || 0)
  const { data, error, loading } = useResource('/complaints', { ...filters, page, size: 20 })
  const categories = useResource('/categories')
  const departments = useResource('/departments')

  const update = (key) => (event) => {
    const next = new URLSearchParams(params)
    if (event.target.value) next.set(key, event.target.value)
    else next.delete(key)
    next.delete('page')
    setParams(next)
  }

  const select = (key, label, anyLabel, options) => (
    <Field label={label}>
      {(props) => (
        <select {...props} className="input" value={filters[key]} onChange={update(key)}>
          <option value="">{anyLabel}</option>
          {options.map(([value, text]) => (
            <option key={value} value={value}>
              {text}
            </option>
          ))}
        </select>
      )}
    </Field>
  )

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>All complaints</h1>
          <p>Every complaint in the city. Open one to assign, edit or close it.</p>
        </div>
      </div>

      <div className="toolbar" role="search" aria-label="Filter complaints">
        {select('status', 'Status', 'Any status', STATUS_ORDER.map((s) => [s, STATUS_LABEL[s]]))}
        {select('categoryId', 'Category', 'Any category', (categories.data ?? []).map((c) => [c.id, c.name]))}
        {select('departmentId', 'Department', 'Any department', (departments.data ?? []).map((d) => [d.id, d.name]))}
        {select('priority', 'Priority', 'Any priority', Object.entries(PRIORITY_LABEL))}
      </div>

      <ErrorNotice error={error} />
      {loading && !data ? (
        <RowsSkeleton />
      ) : data?.items.length ? (
        <>
          <ComplaintRows items={data.items} staff showOfficer />
          <Pager
            data={data}
            onPage={(next) => {
              const nextParams = new URLSearchParams(params)
              nextParams.set('page', next)
              setParams(nextParams)
            }}
          />
        </>
      ) : (
        !error && <Empty title="No complaints match these filters">Change or clear a filter to see more.</Empty>
      )}
    </div>
  )
}
