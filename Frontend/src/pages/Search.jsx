import { useState } from 'react'
import { MagnifyingGlassIcon } from '@phosphor-icons/react'
import { api } from '../api.js'
import { useAuth } from '../authContext.js'
import { ComplaintRows } from '../components/ComplaintRows.jsx'
import { ErrorNotice } from '../components/ui.jsx'
import { useTitle } from '../useTitle.js'

const SCOPE = {
  CITIZEN: 'your complaints',
  OFFICER: 'complaints assigned to you',
  ADMIN: 'all complaints',
}

export default function Search() {
  useTitle('Search')
  const { user } = useAuth()
  const [query, setQuery] = useState('')
  const [results, setResults] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  async function search(event) {
    event.preventDefault()
    const q = query.trim()
    if (q.length < 2) return
    setBusy(true)
    setError(null)
    try {
      const [complaints, knowledge] = await Promise.all([api.get('/search/complaints', { q }), api.get('/search/knowledge', { q })])
      setResults({ q, complaints, knowledge })
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Search</h1>
          <p>Describe what you are looking for in your own words; results match by meaning, not exact words.</p>
        </div>
      </div>

      <form role="search" className="stack" onSubmit={search} style={{ maxWidth: '46rem' }}>
        <label htmlFor="q" className="label">
          Search {SCOPE[user.role]} and civic guidance
        </label>
        <div className="row-actions" style={{ flexWrap: 'nowrap', alignItems: 'stretch' }}>
          <input
            id="q"
            className="input"
            type="search"
            maxLength={300}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="For example: dark street near the school"
          />
          <button type="submit" className="sign sign--go" disabled={busy || query.trim().length < 2}>
            <MagnifyingGlassIcon aria-hidden="true" /> {busy ? 'Searching…' : 'Search'}
          </button>
        </div>
      </form>

      <ErrorNotice error={error} />

      {results && (
        <div className="split" aria-live="polite">
          <section className="section" aria-labelledby="complaint-results">
            <h2 id="complaint-results">Complaints</h2>
            {results.complaints.length ? (
              <ComplaintRows items={results.complaints.map((hit) => hit.complaint)} staff={user.role !== 'CITIZEN'} showOfficer={user.role === 'ADMIN'} />
            ) : (
              <p className="muted">
                No complaints related to “{results.q}”. New complaints become searchable once their AI analysis
                finishes.
              </p>
            )}
          </section>
          <section className="section" aria-labelledby="guidance-results">
            <h2 id="guidance-results">Civic guidance</h2>
            {results.knowledge.length ? (
              <ul className="rows">
                {results.knowledge.map((hit) => (
                  <li key={`${hit.source}-${hit.title}`} className="row">
                    <span className="row__title">{hit.title}</span>
                    <p className="small">{hit.content.length > 260 ? `${hit.content.slice(0, 260).trimEnd()}…` : hit.content}</p>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="muted">No guidance related to “{results.q}”.</p>
            )}
          </section>
        </div>
      )}
    </div>
  )
}
