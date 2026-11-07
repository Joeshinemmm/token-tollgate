import type { KeyboardEvent } from 'react'

interface SegmentedProps<T extends string> {
  label: string
  options: readonly { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
}

/** A row of mutually exclusive buttons: tabs for a view, presets for a filter. Arrow keys move the selection. */
export function Segmented<T extends string>({ label, options, value, onChange }: SegmentedProps<T>) {
  const onKey = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return
    event.preventDefault()
    const index = options.findIndex((option) => option.value === value)
    const step = event.key === 'ArrowRight' ? 1 : -1
    const next = (index + step + options.length) % options.length
    onChange(options[next].value)
    const buttons = event.currentTarget.querySelectorAll<HTMLButtonElement>('button')
    buttons[next]?.focus()
  }

  return (
    <div className="segmented" role="tablist" aria-label={label} onKeyDown={onKey}>
      {options.map((option) => {
        const selected = option.value === value
        return (
          <button key={option.value} type="button" role="tab" aria-selected={selected} tabIndex={selected ? 0 : -1} onClick={() => onChange(option.value)}>
            {option.label}
          </button>
        )
      })}
    </div>
  )
}
