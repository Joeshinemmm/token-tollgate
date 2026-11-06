import type { ReactNode } from 'react'

export type AlertKind = 'error' | 'success' | 'info'

export function Alert({ kind, children, onDismiss }: { kind: AlertKind; children: ReactNode; onDismiss?: () => void }) {
  return (
    <div className={`alert alert-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      <div>{children}</div>
      {onDismiss && (
        <button type="button" className="alert-dismiss" onClick={onDismiss} aria-label="알림 닫기">
          ×
        </button>
      )}
    </div>
  )
}
