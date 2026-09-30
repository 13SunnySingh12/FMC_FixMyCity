import { createBrowserRouter } from 'react-router'
import { RequireAuth } from './components/RequireAuth.jsx'
import { Shell } from './components/Shell.jsx'
import Welcome from './pages/Welcome.jsx'
import SignIn from './pages/SignIn.jsx'
import Register from './pages/Register.jsx'
import MyComplaints from './pages/MyComplaints.jsx'
import NewComplaint from './pages/NewComplaint.jsx'
import ComplaintDetail from './pages/ComplaintDetail.jsx'
import WorkQueue from './pages/WorkQueue.jsx'
import Overview from './pages/admin/Overview.jsx'
import AllComplaints from './pages/admin/AllComplaints.jsx'
import People from './pages/admin/People.jsx'
import Routing from './pages/admin/Routing.jsx'
import Assistant from './pages/Assistant.jsx'
import Search from './pages/Search.jsx'
import NotFound from './pages/NotFound.jsx'

export const router = createBrowserRouter([
  {
    path: '/',
    Component: Shell,
    children: [
      { index: true, Component: Welcome },
      { path: 'signin', Component: SignIn },
      { path: 'register', Component: Register },
      {
        element: <RequireAuth roles={['CITIZEN']} />,
        children: [
          { path: 'complaints', Component: MyComplaints },
          { path: 'complaints/new', Component: NewComplaint },
        ],
      },
      {
        element: <RequireAuth roles={['OFFICER']} />,
        children: [{ path: 'work', Component: WorkQueue }],
      },
      {
        element: <RequireAuth roles={['ADMIN']} />,
        children: [
          { path: 'admin', Component: Overview },
          { path: 'admin/complaints', Component: AllComplaints },
          { path: 'admin/people', Component: People },
          { path: 'admin/routing', Component: Routing },
        ],
      },
      {
        element: <RequireAuth />,
        children: [
          // `back` puts the return-to-list sign in the guide band on phones.
          { path: 'complaints/:id', Component: ComplaintDetail, handle: { back: true } },
          { path: 'assistant', Component: Assistant },
          { path: 'search', Component: Search },
        ],
      },
      { path: '*', Component: NotFound },
    ],
  },
])
