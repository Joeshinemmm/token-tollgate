import { useId, type ReactNode } from 'react'

interface FieldProps {
  label: string
  hint?: string
  error?: string
  className?: string
  /** Receives the id to put on the control and whether it is invalid. */
  children: (id: string, invalid: boolean) => ReactNode
}

/** A label, a control and its hint or error, wired together for screen readers. */
export function Field({ label, hint, error, className, children }: FieldProps) {
  const id = useId()
  let message: ReactNode = null
  if (error) {
    message = (
      <span className="field-error" role="alert">
        {error}
      </span>
    )
  } else if (hint) {
    message = <span className="field-hint">{hint}</span>
  }
  return (
    <div className={className ? `field ${className}` : 'field'}>
      <label className="field-label" htmlFor={id}>
        {label}
      </label>
      {children(id, Boolean(error))}
      {message}
    </div>
  )
}
