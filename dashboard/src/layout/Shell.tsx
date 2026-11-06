import type { ReactNode } from 'react'
import { useAuth } from '../auth/AuthContext'
import { routeHref, type Route } from '../router/useHashRoute'

const NAV: { route: Route; label: string }[] = [
  { route: 'keys', label: '키 관리' },
  { route: 'prices', label: '요금표' },
]

export function Brand() {
  return (
    <div className="brand">
      <span className="brand-mark" aria-hidden="true" />
      <div>
        <div className="brand-name">token-tollgate</div>
        <div className="brand-sub">요금소 관리</div>
      </div>
    </div>
  )
}

export function Shell({ route, children }: { route: Route; children: ReactNode }) {
  const { logout } = useAuth()
  return (
    <div className="shell">
      <aside className="rail">
        <Brand />
        <nav className="nav" aria-label="화면">
          {NAV.map((item) => (
            <a key={item.route} href={routeHref(item.route)} aria-current={item.route === route ? 'page' : undefined}>
              {item.label}
            </a>
          ))}
        </nav>
        <div className="rail-foot">
          <button type="button" className="btn btn-small" onClick={logout}>
            로그아웃
          </button>
        </div>
      </aside>
      <main className="main">{children}</main>
    </div>
  )
}
