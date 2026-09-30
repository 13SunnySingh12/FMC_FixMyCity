import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, Outlet, ScrollRestoration, useLocation, useMatches, useNavigate } from 'react-router'
import { ArrowLeftIcon, ListIcon, SignpostIcon, XIcon } from '@phosphor-icons/react'
import { homeFor, listFor, useAuth } from '../authContext.js'
import { ROLE_LABEL } from '../format.js'

const LINKS = {
  CITIZEN: [
    ['/complaints', 'My complaints'],
    ['/complaints/new', 'Report a problem'],
    ['/assistant', 'Ask FMC'],
    ['/search', 'Search'],
  ],
  OFFICER: [
    ['/work', 'My queue'],
    ['/assistant', 'Ask FMC'],
    ['/search', 'Search'],
  ],
  ADMIN: [
    ['/admin', 'Overview'],
    ['/admin/complaints', 'Complaints'],
    ['/admin/people', 'People'],
    ['/admin/routing', 'Routing'],
    ['/assistant', 'Ask FMC'],
    ['/search', 'Search'],
  ],
}

export function Shell() {
  const { user, logout } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  // Land on the front page with no "return to" state; the next person to sign in starts at their own home.
  const signOut = () => logout().then(() => navigate('/', { replace: true }))
  const [menuOpen, setMenuOpen] = useState(false)
  const main = useRef(null)
  const lastPath = useRef(location.pathname)
  const links = user ? LINKS[user.role] : []
  const matches = useMatches()
  const back = user && matches.at(-1)?.handle?.back ? listFor(user) : null

  // Move focus to the new page so screen readers announce it; skip the initial load.
  // ScrollRestoration handles the scroll position, so focusing must not scroll.
  useEffect(() => {
    if (lastPath.current === location.pathname) return
    lastPath.current = location.pathname
    main.current?.focus({ preventScroll: true })
  }, [location.pathname])

  const nav = links.map(([to, label]) => (
    <NavLink key={to} to={to} end onClick={() => setMenuOpen(false)}>
      {label}
    </NavLink>
  ))

  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="band">
        <div className="container band__inner">
          {back && (
            <Link className="sign sign--quiet band__back" to={back[0]} aria-label={`Back to ${back[1]}`}>
              <ArrowLeftIcon size={24} aria-hidden="true" />
            </Link>
          )}
          <Link className="mark" to={homeFor(user)}>
            <SignpostIcon size={26} weight="fill" aria-hidden="true" />
            FixMyCity
          </Link>
          {user && (
            <>
              <nav className="nav" aria-label="Main">
                {nav}
              </nav>
              <div className="band__user">
                <span className="band__who">
                  {user.name}
                  <span className="visually-hidden">, signed in as</span>
                  <span className="band__role">{ROLE_LABEL[user.role]}</span>
                </span>
                <button type="button" className="sign sign--quiet sign--small" onClick={signOut}>
                  Sign out
                </button>
              </div>
              <button
                type="button"
                className="sign sign--quiet band__menu"
                aria-expanded={menuOpen}
                aria-controls="nav-panel"
                onClick={() => setMenuOpen((open) => !open)}
              >
                {menuOpen ? <XIcon size={24} aria-hidden="true" /> : <ListIcon size={24} aria-hidden="true" />}
                Menu
              </button>
            </>
          )}
          {!user && user !== undefined && location.pathname !== '/signin' && (
            <Link className="sign sign--quiet" to="/signin" style={{ marginLeft: 'auto' }}>
              Sign in
            </Link>
          )}
        </div>
        {user && menuOpen && (
          <nav id="nav-panel" className="container nav-panel" aria-label="Main">
            {nav}
            <button type="button" className="sign sign--quiet" style={{ justifyContent: 'flex-start' }} onClick={signOut}>
              Sign out ({user.name})
            </button>
          </nav>
        )}
      </header>
      <main id="main" ref={main} tabIndex={-1}>
        <div className="container">
          <Outlet />
        </div>
      </main>
      <ScrollRestoration />
    </>
  )
}
