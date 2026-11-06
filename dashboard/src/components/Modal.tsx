import { useEffect, useId, useRef, type MouseEvent, type ReactNode } from 'react'

interface ModalProps {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
}

/** A centered dialog: Esc or the backdrop closes it, focus moves to its first field when it opens. */
export function Modal({ open, title, onClose, children, footer }: ModalProps) {
  const titleId = useId()
  const panel = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, onClose])

  useEffect(() => {
    if (!open) return
    const first =
      panel.current?.querySelector<HTMLElement>('.modal-body input, .modal-body select') ??
      panel.current?.querySelector<HTMLElement>('.modal-foot button')
    first?.focus()
  }, [open])

  if (!open) return null

  const onBackdrop = (event: MouseEvent<HTMLDivElement>) => {
    if (event.target === event.currentTarget) onClose()
  }

  return (
    <div className="modal-backdrop" onMouseDown={onBackdrop}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby={titleId} ref={panel}>
        <div className="modal-head">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="alert-dismiss" onClick={onClose} aria-label="대화상자 닫기">
            ×
          </button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>
  )
}
