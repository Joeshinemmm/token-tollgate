import { useEffect, useState, type RefObject } from 'react'

interface CopyButtonProps {
  text: string
  label?: string
  /** When the clipboard is unavailable, the contents of this element are selected so that Ctrl+C still works. */
  selectOnFailure?: RefObject<HTMLElement | null>
}

type CopyState = 'idle' | 'copied' | 'failed'

function buttonText(state: CopyState, label: string): string {
  switch (state) {
    case 'copied':
      return '복사됨'
    case 'failed':
      return '복사 실패: 선택된 키를 직접 복사하세요'
    case 'idle':
      return label
  }
}

/** Copies [text] to the clipboard and says so for two seconds. */
export function CopyButton({ text, label = '복사', selectOnFailure }: CopyButtonProps) {
  const [state, setState] = useState<CopyState>('idle')

  useEffect(() => {
    if (state === 'idle') return
    const timer = window.setTimeout(() => setState('idle'), 2000)
    return () => window.clearTimeout(timer)
  }, [state])

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text)
      setState('copied')
    } catch {
      selectContents(selectOnFailure?.current)
      setState('failed')
    }
  }

  return (
    <button type="button" className="btn btn-small" onClick={copy}>
      {buttonText(state, label)}
    </button>
  )
}

function selectContents(node: HTMLElement | null | undefined) {
  if (!node) return
  const selection = window.getSelection()
  if (!selection) return
  const range = document.createRange()
  range.selectNodeContents(node)
  selection.removeAllRanges()
  selection.addRange(range)
}
