import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Gelistirmede /api ve /actuator istekleri lokal api servisine gider.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
