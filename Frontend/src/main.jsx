import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router'
import { IconContext } from '@phosphor-icons/react'
import '@fontsource-variable/overpass'
import '@fontsource/overpass-mono/400.css'
import '@fontsource/overpass-mono/600.css'
import './styles/base.css'
import './styles/components.css'
import './styles/pages.css'
import { AuthProvider } from './auth.jsx'
import { router } from './App.jsx'

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <IconContext.Provider value={{ weight: 'bold', size: 20 }}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </IconContext.Provider>
  </StrictMode>,
)
