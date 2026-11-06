import type { ReactNode } from 'react'

export type BadgeTone = 'active' | 'revoked' | 'archived' | 'neutral'

export function Badge({ tone, children }: { tone: BadgeTone; children: ReactNode }) {
  return <span className={`badge badge-${tone}`}>{children}</span>
}
