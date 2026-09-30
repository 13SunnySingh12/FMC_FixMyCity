import { Link, Navigate } from 'react-router'
import { ArrowRightIcon, CameraIcon, ClipboardTextIcon, ArrowCounterClockwiseIcon } from '@phosphor-icons/react'
import { homeFor, useAuth } from '../authContext.js'
import { Route } from '../components/ui.jsx'
import { useTitle } from '../useTitle.js'

const STAGES = [
  ['Submitted', 'Recorded and waiting for an administrator to assign an officer.'],
  ['Assigned', 'An officer in the responsible department now owns it.'],
  ['In progress', 'The officer is working on it and recording what was done.'],
  ['Resolved', 'Fixed, with investigation notes and photos of the result.'],
  ['Closed', 'You confirmed the fix with feedback, or an administrator closed it.'],
]

export default function Welcome() {
  useTitle()
  const { user } = useAuth()
  if (user) {
    return <Navigate to={homeFor(user)} replace />
  }

  return (
    <div className="page welcome">
      <section className="welcome__intro">
        <h1>Report a civic problem. Follow it all the way to the fix.</h1>
        <p className="lead">
          Potholes, garbage, broken streetlights, blocked drains and water problems, recorded with your photo and
          tracked at every step.
        </p>
        <div className="row-actions">
          <Link className="sign sign--go" to="/register">
            Create an account <ArrowRightIcon aria-hidden="true" />
          </Link>
          <Link className="sign sign--plain" to="/signin">
            Sign in
          </Link>
        </div>
      </section>

      <section className="section" aria-labelledby="route-heading">
        <h2 id="route-heading">Every complaint travels the same route</h2>
        <p className="muted">This complaint is in progress: an officer is working on it right now.</p>
        <Route status="IN_PROGRESS" />
        <dl className="stations">
          {STAGES.map(([stage, meaning]) => (
            <div key={stage}>
              <dt>{stage}</dt>
              <dd>{meaning}</dd>
            </div>
          ))}
        </dl>
      </section>

      <section className="section" aria-labelledby="after-heading">
        <h2 id="after-heading">What happens after you report</h2>
        <ul className="steps">
          <li>
            <CameraIcon size={22} aria-hidden="true" />
            <p>
              AI reads your description and photo and suggests the category, priority and department, so the right team
              gets it sooner.
            </p>
          </li>
          <li>
            <ClipboardTextIcon size={22} aria-hidden="true" />
            <p>An officer records notes and photos of the repair before marking it resolved.</p>
          </li>
          <li>
            <ArrowCounterClockwiseIcon size={22} aria-hidden="true" />
            <p>If the problem is still there, reopen it. If it is fixed, rate the work and the complaint closes.</p>
          </li>
        </ul>
      </section>
    </div>
  )
}
