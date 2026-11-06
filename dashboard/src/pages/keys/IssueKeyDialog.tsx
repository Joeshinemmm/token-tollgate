import { useRef, useState, type FormEvent } from 'react'
import { errorMessage } from '../../api/client'
import type { IssuedKey } from '../../api/types'
import { Alert } from '../../components/Alert'
import { CopyButton } from '../../components/CopyButton'
import { Field } from '../../components/Field'
import { Modal } from '../../components/Modal'

interface IssueKeyDialogProps {
  open: boolean
  projectName: string
  onIssue: (name: string) => Promise<IssuedKey>
  onClose: () => void
}

/** Asks for a name, then shows the plaintext key exactly once, on a ticket. */
export function IssueKeyDialog({ open, projectName, onIssue, onClose }: IssueKeyDialogProps) {
  const [name, setName] = useState('')
  const [issued, setIssued] = useState<IssuedKey | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const keyNode = useRef<HTMLElement>(null)

  const close = () => {
    setName('')
    setIssued(null)
    setError(null)
    onClose()
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) {
      setError('키 이름을 입력하세요.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      setIssued(await onIssue(trimmed))
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  if (issued) {
    return (
      <Modal
        open={open}
        title="키 발급 완료"
        onClose={close}
        footer={
          <button type="button" className="btn btn-primary" onClick={close}>
            닫기
          </button>
        }
      >
        <p>
          <strong>{projectName}</strong> 프로젝트의 키 <strong>{issued.name}</strong>이(가) 발급되었습니다.
        </p>
        <div className="ticket">
          <span className="ticket-stamp">한 번만 표시</span>
          <span className="field-label">가상 키</span>
          <code className="ticket-key" data-testid="issued-key" ref={keyNode}>
            {issued.key}
          </code>
          <div className="row">
            <CopyButton text={issued.key} selectOnFailure={keyNode} />
            <span className="muted" style={{ fontSize: 13 }}>
              이 키는 다시 볼 수 없습니다. 지금 복사해 도구에 넣으세요.
            </span>
          </div>
        </div>
      </Modal>
    )
  }

  return (
    <Modal
      open={open}
      title="키 발급"
      onClose={close}
      footer={
        <>
          <button type="button" className="btn" onClick={close} disabled={busy}>
            취소
          </button>
          <button type="submit" form="issue-key-form" className="btn btn-primary" disabled={busy}>
            {busy ? '발급 중…' : '발급'}
          </button>
        </>
      }
    >
      <p className="muted">
        <strong>{projectName}</strong> 프로젝트에 새 키를 만듭니다. 이름은 어느 도구가 쓰는 키인지 알아보는 용도입니다.
      </p>
      {error && <Alert kind="error">{error}</Alert>}
      <form id="issue-key-form" onSubmit={submit}>
        <Field label="키 이름" hint="예: claude-code, 노트북 cursor">
          {(id, invalid) => (
            <input id={id} className="input" value={name} onChange={(event) => setName(event.target.value)} aria-invalid={invalid} autoFocus />
          )}
        </Field>
      </form>
    </Modal>
  )
}
