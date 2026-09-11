const version = import.meta.env.VITE_APP_VERSION ?? 'dev'
const environment = import.meta.env.VITE_APP_ENV ?? 'LOCAL'

export default function App() {
  return (
    <main className="app">
      <h1>Kesinti Haritası</h1>
      <p>İskelet sürüm. Harita Faz 4'te geliyor.</p>
      <span className="badge">
        v{version} - {environment}
      </span>
    </main>
  )
}
