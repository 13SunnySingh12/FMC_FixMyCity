import { createContext, useContext } from 'react'

export const AuthContext = createContext(null)

export function useAuth() {
  return useContext(AuthContext)
}

/** Where each role starts after signing in. */
export function homeFor(user) {
  if (!user) return '/'
  return { CITIZEN: '/complaints', OFFICER: '/work', ADMIN: '/admin' }[user.role] ?? '/'
}

/** The complaint list a role returns to from a complaint: [path, label]. */
export function listFor(user) {
  return {
    CITIZEN: ['/complaints', 'My complaints'],
    OFFICER: ['/work', 'My queue'],
    ADMIN: ['/admin/complaints', 'All complaints'],
  }[user.role]
}
