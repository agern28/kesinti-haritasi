import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Gelistirmede /api istekleri lokal api servisine gider.
// "Yenilikler" penceresi repo kokundeki CHANGELOG.md'yi okuyor; dev sunucusunun oraya erismesi icin fs.allow.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
    fs: {
      allow: ['..'],
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.js'],
  },
})
