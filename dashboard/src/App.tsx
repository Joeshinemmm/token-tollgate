import { useAuth } from './auth/AuthContext'
import { AuthProvider } from './auth/AuthProvider'
import { Shell } from './layout/Shell'
import { LoginPage } from './pages/LoginPage'
import { KeysPage } from './pages/keys/KeysPage'
import { PricesPage } from './pages/prices/PricesPage'
import { useHashRoute } from './router/useHashRoute'

function Screen() {
  const { token } = useAuth()
  const [route] = useHashRoute()
  if (!token) return <LoginPage />
  return <Shell route={route}>{route === 'prices' ? <PricesPage /> : <KeysPage />}</Shell>
}

export default function App() {
  return (
    <AuthProvider>
      <Screen />
    </AuthProvider>
  )
}
