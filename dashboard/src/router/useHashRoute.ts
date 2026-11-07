import { useCallback, useEffect, useState } from 'react'

/** The screens of the dashboard. */
export type Route = 'overview' | 'requests' | 'keys' | 'prices'

const ROUTES: readonly Route[] = ['overview', 'requests', 'keys', 'prices']

export const DEFAULT_ROUTE: Route = 'overview'

/** A parsed hash: the screen and the query string after `?` (without the `?`), where screens keep their filters. */
export interface HashLocation {
  route: Route
  search: string
}

export function parseHash(hash: string): HashLocation {
  const body = hash.replace(/^#\/?/, '')
  const at = body.indexOf('?')
  const path = at === -1 ? body : body.slice(0, at)
  const search = at === -1 ? '' : body.slice(at + 1)
  const name = path.split('/')[0]
  const route = (ROUTES as readonly string[]).includes(name) ? (name as Route) : DEFAULT_ROUTE
  return { route, search }
}

export function parseRoute(hash: string): Route {
  return parseHash(hash).route
}

export function routeHref(route: Route, search = ''): string {
  return search ? `#/${route}?${search}` : `#/${route}`
}

/** The current screen and query from `location.hash`, kept in sync with the back button and links. */
export function useHashRoute(): [HashLocation, (route: Route, search?: string) => void] {
  const [location, setLocation] = useState<HashLocation>(() => parseHash(window.location.hash))

  useEffect(() => {
    const onChange = () => setLocation(parseHash(window.location.hash))
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])

  const navigate = useCallback((route: Route, search = '') => {
    window.location.hash = routeHref(route, search)
  }, [])

  return [location, navigate]
}
