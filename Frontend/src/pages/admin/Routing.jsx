import { useState } from 'react'
import { PlusIcon } from '@phosphor-icons/react'
import { api } from '../../api.js'
import { ErrorNotice, Field, RowsSkeleton } from '../../components/ui.jsx'
import { useTitle } from '../../useTitle.js'
import { useResource } from '../../useResource.js'

export default function Routing() {
  useTitle('Routing')
  const departments = useResource('/departments')
  const categories = useResource('/categories')
  const refresh = () => {
    departments.reload()
    categories.reload()
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Routing</h1>
          <p>Each category routes complaints to the department that handles it. The civic assistant picks up changes automatically.</p>
        </div>
      </div>

      <section className="section" aria-labelledby="categories-heading">
        <h2 id="categories-heading">Categories</h2>
        <ErrorNotice error={categories.error} />
        {categories.loading && !categories.data ? (
          <RowsSkeleton count={3} />
        ) : (
          <ul className="rows">
            {(categories.data ?? []).map((category) => (
              <EditableRow
                key={category.id}
                item={category}
                kind="category"
                summary={`Goes to ${category.departmentName ?? 'no department'}`}
                departments={departments.data ?? []}
                onChanged={refresh}
              />
            ))}
          </ul>
        )}
        <AddForm kind="category" departments={departments.data ?? []} onAdded={refresh} />
      </section>

      <section className="section" aria-labelledby="departments-heading">
        <h2 id="departments-heading">Departments</h2>
        <ErrorNotice error={departments.error} />
        {departments.loading && !departments.data ? (
          <RowsSkeleton count={3} />
        ) : (
          <ul className="rows">
            {(departments.data ?? []).map((department) => (
              <EditableRow key={department.id} item={department} kind="department" onChanged={refresh} />
            ))}
          </ul>
        )}
        <AddForm kind="department" onAdded={refresh} />
      </section>
    </div>
  )
}

const endpoint = (kind) => (kind === 'category' ? '/admin/categories' : '/admin/departments')

function EditableRow({ item, kind, summary, departments, onChanged }) {
  const [mode, setMode] = useState(null) // null | 'edit' | 'delete'
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  async function act(request) {
    setBusy(true)
    setError(null)
    try {
      await request()
      setMode(null)
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
        <span className="manage-row__title">{item.name}</span>
        {summary && <span className="small">{summary}</span>}
        {item.description && <span className="small muted">{item.description}</span>}
        <ErrorNotice error={error} />
        {mode === 'edit' && (
          <ItemForm
            kind={kind}
            initial={item}
            departments={departments}
            busy={busy}
            submitLabel="Save changes"
            onCancel={() => setMode(null)}
            onSubmit={(body) => act(() => api.put(`${endpoint(kind)}/${item.id}`, body))}
          />
        )}
      </div>
      {mode !== 'edit' && (
        <div className="row-actions">
          {mode === 'delete' ? (
            <>
              <button type="button" className="sign sign--stop sign--small" disabled={busy} onClick={() => act(() => api.delete(`${endpoint(kind)}/${item.id}`))}>
                Delete {item.name}
              </button>
              <button type="button" className="sign sign--quiet sign--small" onClick={() => setMode(null)}>
                Cancel
              </button>
            </>
          ) : (
            <>
              <button type="button" className="sign sign--plain sign--small" onClick={() => setMode('edit')}>
                Edit
              </button>
              <button type="button" className="sign sign--quiet sign--small" onClick={() => setMode('delete')}>
                Delete
              </button>
            </>
          )}
        </div>
      )}
    </li>
  )
}

function AddForm({ kind, departments, onAdded }) {
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  if (!open) {
    return (
      <button type="button" className="sign sign--plain sign--small" style={{ width: 'fit-content' }} onClick={() => setOpen(true)}>
        <PlusIcon aria-hidden="true" /> Add a {kind}
      </button>
    )
  }
  return (
    <>
      <ErrorNotice error={error} />
      <ItemForm
        kind={kind}
        initial={{ name: '', description: '', departmentId: '' }}
        departments={departments}
        busy={busy}
        submitLabel={`Add ${kind}`}
        onCancel={() => setOpen(false)}
        onSubmit={async (body) => {
          setBusy(true)
          setError(null)
          try {
            await api.post(endpoint(kind), body)
            setOpen(false)
            onAdded()
          } catch (err) {
            setError(err)
          } finally {
            setBusy(false)
          }
        }}
      />
    </>
  )
}

function ItemForm({ kind, initial, departments, busy, submitLabel, onSubmit, onCancel }) {
  const [name, setName] = useState(initial.name ?? '')
  const [description, setDescription] = useState(initial.description ?? '')
  const [departmentId, setDepartmentId] = useState(String(initial.departmentId ?? ''))
  const [error, setError] = useState(null)

  return (
    <form
      className="inline-form"
      style={{ maxWidth: '36rem' }}
      onSubmit={(event) => {
        event.preventDefault()
        if (!name.trim()) return setError('Give it a name.')
        if (kind === 'category' && !departmentId) return setError('Choose the department that handles it.')
        onSubmit({ name: name.trim(), description: description.trim() || null, ...(kind === 'category' && { departmentId: Number(departmentId) }) })
      }}
    >
      <Field label="Name" error={error}>
        {(props) => <input {...props} className="input" maxLength={kind === 'category' ? 80 : 120} value={name} onChange={(e) => setName(e.target.value)} />}
      </Field>
      <Field label="Description" optional>
        {(props) => <input {...props} className="input" maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)} />}
      </Field>
      {kind === 'category' && (
        <Field label="Handled by">
          {(props) => (
            <select {...props} className="input" value={departmentId} onChange={(e) => setDepartmentId(e.target.value)}>
              <option value="">Choose a department</option>
              {departments.map((department) => (
                <option key={department.id} value={department.id}>
                  {department.name}
                </option>
              ))}
            </select>
          )}
        </Field>
      )}
      <div className="row-actions">
        <button type="submit" className="sign sign--go sign--small" disabled={busy}>
          {busy ? 'Saving…' : submitLabel}
        </button>
        <button type="button" className="sign sign--quiet sign--small" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}
