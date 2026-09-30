import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { CameraIcon, TrashIcon } from '@phosphor-icons/react'
import { api } from '../api.js'
import { ErrorNotice, Field, Notice } from '../components/ui.jsx'
import { useTitle } from '../useTitle.js'
import { useResource } from '../useResource.js'

const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp']
const MAX_IMAGE_BYTES = 5 * 1024 * 1024

function validate(form) {
  const errors = {}
  if (form.title.trim().length < 5) errors.title = 'Give the complaint a title of at least 5 characters.'
  if (form.description.trim().length < 20) errors.description = 'Describe the problem in at least 20 characters.'
  if (!form.location.trim()) errors.location = 'Say where the problem is.'
  if (!form.categoryId) errors.categoryId = 'Choose the category that fits best.'
  return errors
}

export default function NewComplaint() {
  useTitle('Report a problem')
  const navigate = useNavigate()
  const categories = useResource('/categories')
  // One id per form: if a submit is retried after a network drop, the server returns the same complaint.
  const [requestId] = useState(() => crypto.randomUUID())
  const [form, setForm] = useState({ title: '', description: '', location: '', categoryId: '' })
  const [photo, setPhoto] = useState(null)
  const [errors, setErrors] = useState({})
  const [submitError, setSubmitError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [assist, setAssist] = useState({ busy: false, error: null, suggestion: null })

  useEffect(() => () => photo && URL.revokeObjectURL(photo.preview), [photo])

  const set = (key) => (event) => setForm((current) => ({ ...current, [key]: event.target.value }))

  function choosePhoto(event) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    if (!IMAGE_TYPES.includes(file.type)) {
      setErrors((current) => ({ ...current, photo: 'Choose a JPEG, PNG or WebP photo.' }))
      return
    }
    if (file.size > MAX_IMAGE_BYTES) {
      setErrors((current) => ({ ...current, photo: 'Choose a photo of 5 MB or less.' }))
      return
    }
    setErrors(({ photo: _removed, ...rest }) => rest)
    setPhoto({ file, preview: URL.createObjectURL(file) })
  }

  async function improve() {
    setAssist({ busy: true, error: null, suggestion: null })
    try {
      const suggestion = await api.post('/ai/write', form)
      setAssist({ busy: false, error: null, suggestion })
    } catch (error) {
      setAssist({ busy: false, error, suggestion: null })
    }
  }

  function useSuggestion() {
    const { title, description } = assist.suggestion
    setForm((current) => ({ ...current, title: title.slice(0, 150), description }))
    setAssist({ busy: false, error: null, suggestion: null })
  }

  async function submit(event) {
    event.preventDefault()
    const found = validate(form)
    setErrors(found)
    if (Object.keys(found).length) return
    setBusy(true)
    setSubmitError(null)
    const body = new FormData()
    Object.entries({ ...form, requestId }).forEach(([key, value]) => body.append(key, typeof value === 'string' ? value.trim() : value))
    if (photo) body.append('image', photo.file)
    try {
      const complaint = await api.upload('/complaints', body)
      navigate(`/complaints/${complaint.id}`, { replace: true, state: { justSubmitted: true } })
    } catch (error) {
      setSubmitError(error)
      setErrors(error.fieldErrors ?? {})
      setBusy(false)
    }
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Report a problem</h1>
          <p>Tell us what is wrong and where. A photo helps the right team act faster.</p>
        </div>
      </div>

      <form className="form" onSubmit={submit} noValidate style={{ maxWidth: '46rem' }}>
        {submitError && !Object.keys(submitError.fieldErrors ?? {}).length && <ErrorNotice error={submitError} />}

        <Field label="Title" hint="A short summary, like “Deep pothole outside the bus stop”." error={errors.title}>
          {(props) => <input {...props} className="input" maxLength={150} value={form.title} onChange={set('title')} />}
        </Field>

        <Field
          label="Description"
          hint="What is the problem, since when, and is anyone at risk?"
          error={errors.description}
        >
          {(props) => (
            <textarea {...props} className="input" rows={6} maxLength={5000} value={form.description} onChange={set('description')} />
          )}
        </Field>

        <div className="stack">
          <div className="row-actions">
            <button
              type="button"
              className="sign sign--plain sign--small"
              onClick={improve}
              disabled={assist.busy || form.description.trim().length < 10}
              aria-busy={assist.busy}
            >
              {assist.busy ? 'Improving…' : 'Improve with AI'}
            </button>
            <span className="field__hint">Suggests clearer wording. Nothing changes unless you choose it.</span>
          </div>
          <ErrorNotice error={assist.error} />
          {assist.suggestion && (
            <div className="panel" aria-live="polite">
              <h2>Suggested wording</h2>
              <p className="row__title">{assist.suggestion.title}</p>
              <p className="description">{assist.suggestion.description}</p>
              {assist.suggestion.missingDetails.length > 0 && (
                <div className="stack" style={{ gap: 4 }}>
                  <p className="label">You could also mention</p>
                  <ul>
                    {assist.suggestion.missingDetails.map((detail) => (
                      <li key={detail}>{detail}</li>
                    ))}
                  </ul>
                </div>
              )}
              <div className="row-actions">
                <button type="button" className="sign sign--go sign--small" onClick={useSuggestion}>
                  Use this wording
                </button>
                <button type="button" className="sign sign--quiet sign--small" onClick={() => setAssist({ busy: false, error: null, suggestion: null })}>
                  Keep mine
                </button>
              </div>
            </div>
          )}
        </div>

        <Field label="Location" hint="Street, landmark or area, as precisely as you can." error={errors.location}>
          {(props) => <input {...props} className="input" maxLength={300} autoComplete="street-address" value={form.location} onChange={set('location')} />}
        </Field>

        <fieldset className="field" aria-describedby={errors.categoryId ? 'category-error' : undefined}>
          <legend className="label">Category</legend>
          {categories.error && <ErrorNotice error={categories.error} />}
          {categories.loading && !categories.data && <p className="muted">Loading categories…</p>}
          <div className="choices" style={{ marginTop: 8 }}>
            {(categories.data ?? []).map((category) => (
              <label key={category.id} className="choice">
                <input
                  type="radio"
                  name="category"
                  value={category.id}
                  checked={String(form.categoryId) === String(category.id)}
                  onChange={() => setForm((current) => ({ ...current, categoryId: category.id }))}
                />
                <span className="choice__title">{category.name}</span>
                {category.description && <span className="choice__hint">{category.description}</span>}
              </label>
            ))}
          </div>
          {errors.categoryId && (
            <p className="field__error" id="category-error">
              {errors.categoryId}
            </p>
          )}
        </fieldset>

        <div className="field">
          <span className="label">
            Photo <span className="field__optional">(optional)</span>
          </span>
          {photo ? (
            <div className="thumbs">
              <div className="thumb">
                <img src={photo.preview} alt="Selected photo of the problem" />
                <button type="button" className="sign sign--quiet sign--small" onClick={() => setPhoto(null)}>
                  <TrashIcon aria-hidden="true" /> Remove photo
                </button>
              </div>
            </div>
          ) : (
            <label className="drop">
              <input type="file" accept={IMAGE_TYPES.join(',')} onChange={choosePhoto} aria-describedby="photo-hint" />
              <CameraIcon size={28} aria-hidden="true" />
              <span className="label">Add a photo of the problem</span>
              <span className="field__hint" id="photo-hint">
                JPEG, PNG or WebP, up to 5 MB. Keep people's faces and number plates out of the frame if you can.
              </span>
            </label>
          )}
          {errors.photo && <p className="field__error">{errors.photo}</p>}
        </div>

        {busy && <Notice live>Uploading your complaint…</Notice>}
        <div className="row-actions">
          <button type="submit" className="sign sign--go" disabled={busy}>
            {busy ? 'Submitting…' : 'Submit complaint'}
          </button>
        </div>
      </form>
    </div>
  )
}
