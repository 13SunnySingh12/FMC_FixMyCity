import { useRef, useState } from 'react'
import { PaperPlaneTiltIcon } from '@phosphor-icons/react'
import { api } from '../api.js'
import { ErrorNotice } from '../components/ui.jsx'
import { useTitle } from '../useTitle.js'

const SUGGESTIONS = [
  'Which department handles streetlights?',
  "What does 'In progress' mean?",
  'How do I report a drainage problem?',
  'My complaint was resolved but the problem is still there. What can I do?',
]

export default function Assistant() {
  useTitle('Ask FMC')
  const [question, setQuestion] = useState('')
  const [turns, setTurns] = useState([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const input = useRef(null)

  async function ask(text) {
    const asked = text.trim()
    if (asked.length < 3 || busy) return
    setBusy(true)
    setError(null)
    try {
      const answer = await api.post('/assistant/ask', { question: asked })
      setTurns((current) => [...current, { question: asked, answer }])
      setQuestion('')
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
      input.current?.focus()
    }
  }

  return (
    <div className="page" style={{ maxWidth: '52rem' }}>
      <div className="page-head">
        <div>
          <h1>Ask FMC</h1>
          <p>Answers come only from FixMyCity's own guidance, and show where they came from.</p>
        </div>
      </div>

      {turns.length === 0 && (
        <section className="section" aria-labelledby="try-heading">
          <h2 id="try-heading">Try asking</h2>
          <div className="row-actions">
            {SUGGESTIONS.map((suggestion) => (
              <button key={suggestion} type="button" className="sign sign--plain sign--small" style={{ whiteSpace: 'normal', textAlign: 'left' }} onClick={() => ask(suggestion)} disabled={busy}>
                {suggestion}
              </button>
            ))}
          </div>
        </section>
      )}

      <div className="qa" aria-live="polite">
        {turns.map(({ question: asked, answer }, i) => (
          <div key={i} className="qa">
            <p className="qa__q">{asked}</p>
            <div className="qa__a">
              <p style={{ whiteSpace: 'pre-line' }}>{answer.answer}</p>
              {answer.grounded ? (
                <div className="sources">
                  <span className="label">From FixMyCity guidance</span>
                  <ul>
                    {answer.sources.map((source) => (
                      <li key={`${source.source}-${source.title}`}>{source.title}</li>
                    ))}
                  </ul>
                </div>
              ) : (
                <p className="small muted">No matching FixMyCity guidance was found, so no answer was generated.</p>
              )}
            </div>
          </div>
        ))}
        {busy && <p className="muted">Finding an answer in FixMyCity guidance…</p>}
      </div>

      <ErrorNotice error={error} />

      <form
        className="stack"
        onSubmit={(event) => {
          event.preventDefault()
          ask(question)
        }}
      >
        <label htmlFor="question" className="label">
          Your question
        </label>
        <div className="row-actions" style={{ flexWrap: 'nowrap', alignItems: 'stretch' }}>
          <input
            ref={input}
            id="question"
            className="input"
            maxLength={500}
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            placeholder="For example: who fixes blocked drains?"
          />
          <button type="submit" className="sign sign--go" disabled={busy || question.trim().length < 3}>
            <PaperPlaneTiltIcon aria-hidden="true" /> Ask
          </button>
        </div>
      </form>
    </div>
  )
}
