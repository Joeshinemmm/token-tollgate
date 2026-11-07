import { useAuth } from './auth/AuthContext'
import { AuthProvider } from './auth/AuthProvider'
import { Shell } from './layout/Shell'
import { LoginPage } from './pages/LoginPage'
import { KeysPage } from './pages/keys/KeysPage'
import { OverviewPage } from './pages/overview/OverviewPage'
import { PricesPage } from './pages/prices/PricesPage'
import { RequestsPage } from './pages/requests/RequestsPage'
import { useHashRoute, type Route } from './router/useHashRoute'

function screenFor(route: Route) {
  switch (route) {
    case 'requests':
      return <RequestsPage />
    case 'keys':
      return <KeysPage />
    case 'prices':
      return <PricesPage />
    default:
      return <OverviewPage />
  }
}

function Screen() {
  const { token } = useAuth()
  const [{ route }] = useHashRoute()
  if (!token) return <LoginPage />
  return <Shell route={route}>{screenFor(route)}</Shell>
}

export default function App() {
  return (
    <AuthProvider>
      <Screen />
    </AuthProvider>
  )
}
