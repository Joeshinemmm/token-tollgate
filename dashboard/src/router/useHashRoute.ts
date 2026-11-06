import { useCallback, useEffect, useState } from 'react'

/** The screens of the dashboard. `overview` and `requests` arrive in 010. */
export type Route = 'keys' | 'prices' | 'overview' | 'requests'

const ROUTES: readonly Route[] = ['keys', 'prices', 'overview', 'requests']

export const DEFAULT_ROUTE: Route = 'keys'

export function parseRoute(hash: string): Route {
  const name = hash.replace(/^#\/?/, '').split(/[/?]/)[0]
  return (ROUTES as readonly string[]).includes(name) ? (name as Route) : DEFAULT_ROUTE
}

export function routeHref(route: Route): string {
  return `#/${route}`
}

/** The current screen from `location.hash`, kept in sync with the back button and links. */
export function useHashRoute(): [Route, (route: Route) => void] {
  const [route, setRoute] = useState<Route>(() => parseRoute(window.location.hash))

  useEffect(() => {
    const onChange = () => setRoute(parseRoute(window.location.hash))
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])

  const navigate = useCallback((next: Route) => {
    window.location.hash = routeHref(next)
  }, [])

  return [route, navigate]
}
