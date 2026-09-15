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
    // npm run test:coverage. lcov Sonar icin, text-summary CI logu icin.
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'lcov'],
      include: ['src/**/*.{js,jsx}'],
      exclude: ['src/**/*.test.{js,jsx}', 'src/test/**', 'src/main.jsx'],
      // Alt sinir: 2026-09-15'te satir %53. Harita (Leaflet) ve App jsdom'da test edilmiyor.
      thresholds: {
        lines: 50,
        statements: 50,
        branches: 50,
        functions: 45,
      },
    },
  },
})
