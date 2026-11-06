import { useState, type FormEvent } from 'react'
import type { Project } from '../../api/types'
import { Badge } from '../../components/Badge'
import { formatDate } from '../../format'

interface ProjectListProps {
  projects: Project[]
  selectedId: string | null
  includeArchived: boolean
  onSelect: (id: string) => void
  onToggleIncludeArchived: (value: boolean) => void
  onCreate: (name: string) => Promise<void>
  onRename: (project: Project, name: string) => Promise<void>
  onToggleArchive: (project: Project) => Promise<void>
}

export function ProjectList({
  projects,
  selectedId,
  includeArchived,
  onSelect,
  onToggleIncludeArchived,
  onCreate,
  onRename,
  onToggleArchive,
}: ProjectListProps) {
  const [newName, setNewName] = useState('')
  const [renamingId, setRenamingId] = useState<string | null>(null)
  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)

  const create = async (event: FormEvent) => {
    event.preventDefault()
    const name = newName.trim()
    if (!name) return
    setBusy(true)
    try {
      await onCreate(name)
      setNewName('')
    } finally {
      setBusy(false)
    }
  }

  const saveRename = async (project: Project) => {
    const name = draft.trim()
    setRenamingId(null)
    if (name && name !== project.name) await onRename(project, name)
  }

  return (
    <section className="panel" aria-labelledby="projects-heading">
      <div className="panel-head">
        <h2 id="projects-heading">프로젝트</h2>
        <label className="row" style={{ fontSize: 13 }}>
          <input type="checkbox" checked={includeArchived} onChange={(event) => onToggleIncludeArchived(event.target.checked)} />
          보관된 프로젝트 보기
        </label>
      </div>
      <form className="row" style={{ padding: '12px 16px', borderBottom: '1px solid var(--line)' }} onSubmit={create}>
        <input
          className="input"
          style={{ flex: 1 }}
          aria-label="새 프로젝트 이름"
          placeholder="새 프로젝트 이름"
          value={newName}
          onChange={(event) => setNewName(event.target.value)}
        />
        <button type="submit" className="btn btn-primary" disabled={busy || !newName.trim()}>
          추가
        </button>
      </form>
      {projects.length === 0 ? (
        <p className="empty">프로젝트가 없습니다. 위에서 첫 프로젝트를 만드세요.</p>
      ) : (
        <ul className="project-list">
          {projects.map((project) => {
            const archived = project.archivedAt !== null
            const selected = project.id === selectedId
            return (
              <li key={project.id} className={selected ? 'project-item is-selected' : 'project-item'}>
                {renamingId === project.id ? (
                  <div className="project-select">
                    <input
                      className="input"
                      aria-label="프로젝트 이름"
                      value={draft}
                      autoFocus
                      onChange={(event) => setDraft(event.target.value)}
                      onBlur={() => void saveRename(project)}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter') void saveRename(project)
                        if (event.key === 'Escape') setRenamingId(null)
                      }}
                    />
                  </div>
                ) : (
                  <button type="button" className="project-select" onClick={() => onSelect(project.id)} aria-current={selected ? 'true' : undefined}>
                    <div className="project-name">{project.name}</div>
                    <div className="project-meta">
                      {formatDate(project.createdAt)} 생성 {archived && <Badge tone="archived">보관됨</Badge>}
                    </div>
                  </button>
                )}
                <div className="row" style={{ flexWrap: 'nowrap' }}>
                  <button
                    type="button"
                    className="btn btn-small"
                    onClick={() => {
                      setRenamingId(project.id)
                      setDraft(project.name)
                    }}
                    aria-label={`${project.name} 이름 변경`}
                  >
                    이름 변경
                  </button>
                  <button type="button" className="btn btn-small" onClick={() => void onToggleArchive(project)} aria-label={`${project.name} ${archived ? '보관 해제' : '보관'}`}>
                    {archived ? '보관 해제' : '보관'}
                  </button>
                </div>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
