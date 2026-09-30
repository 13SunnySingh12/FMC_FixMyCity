import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The dev server proxies /api so the browser sees one origin, like production behind nginx:
// the SameSite=Strict login cookie then flows without any CORS configuration.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
  },
})
