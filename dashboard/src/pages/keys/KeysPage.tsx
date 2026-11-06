import { useEffect, useReducer, useState } from 'react'
import { errorMessage } from '../../api/client'
import type { Project, VirtualKey } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { Alert } from '../../components/Alert'
import { Badge } from '../../components/Badge'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { IssueKeyDialog } from './IssueKeyDialog'
import { KeyTable } from './KeyTable'
import { ProjectList } from './ProjectList'

const increment = (count: number) => count + 1

interface LoadedKeys {
  projectId: string
  keys: VirtualKey[]
}

export function KeysPage() {
  const { api } = useAuth()
  const [projects, setProjects] = useState<Project[]>([])
  const [projectsVersion, reloadProjects] = useReducer(increment, 0)
  const [includeArchived, setIncludeArchived] = useState(false)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [loadedKeys, setLoadedKeys] = useState<LoadedKeys | null>(null)
  const [keysVersion, reloadKeys] = useReducer(increment, 0)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [issueOpen, setIssueOpen] = useState(false)
  const [revoking, setRevoking] = useState<VirtualKey | null>(null)
  const [revokeBusy, setRevokeBusy] = useState(false)

  useEffect(() => {
    let cancelled = false
    api
      .listProjects(includeArchived)
      .then((list) => {
        if (cancelled) return
        setProjects(list)
        setSelectedId((current) => (current && list.some((project) => project.id === current) ? current : (list[0]?.id ?? null)))
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err))
      })
    return () => {
      cancelled = true
    }
  }, [api, includeArchived, projectsVersion])

  useEffect(() => {
    if (!selectedId) return
    let cancelled = false
    api
      .listKeys(selectedId)
      .then((keys) => {
        if (!cancelled) setLoadedKeys({ projectId: selectedId, keys })
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err))
      })
    return () => {
      cancelled = true
    }
  }, [api, selectedId, keysVersion])

  const selected = projects.find((project) => project.id === selectedId) ?? null
  const selectedArchived = selected?.archivedAt != null
  const keys = loadedKeys && loadedKeys.projectId === selectedId ? loadedKeys.keys : null

  const run = async (action: () => Promise<void>, success?: string) => {
    setError(null)
    try {
      await action()
      setNotice(success ?? null)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  const createProject = (name: string) =>
    run(async () => {
      const created = await api.createProject(name)
      setSelectedId(created.id)
      reloadProjects()
    }, `프로젝트 ${name}을(를) 만들었습니다.`)

  const renameProject = (project: Project, name: string) =>
    run(async () => {
      await api.updateProject(project.id, { name })
      reloadProjects()
    })

  const toggleArchive = (project: Project) =>
    run(async () => {
      await api.updateProject(project.id, { archived: project.archivedAt === null })
      reloadProjects()
    })

  const issueKey = async (name: string) => {
    if (!selected) throw new Error('프로젝트를 먼저 선택하세요.')
    return api.issueKey(selected.id, name)
  }

  const closeIssue = () => {
    setIssueOpen(false)
    reloadKeys()
  }

  const confirmRevoke = async () => {
    if (!revoking) return
    setRevokeBusy(true)
    await run(async () => {
      await api.revokeKey(revoking.id)
      reloadKeys()
    }, `키 ${revoking.keyPrefix}…를 폐기했습니다.`)
    setRevokeBusy(false)
    setRevoking(null)
  }

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>키 관리</h1>
          <p>프로젝트마다 가상 키를 발급해 도구에 넣습니다. 키 평문은 발급할 때 한 번만 보입니다.</p>
        </div>
      </div>
      {error && (
        <Alert kind="error" onDismiss={() => setError(null)}>
          {error}
        </Alert>
      )}
      {notice && (
        <Alert kind="success" onDismiss={() => setNotice(null)}>
          {notice}
        </Alert>
      )}
      <div className="keys-layout">
        <ProjectList
          projects={projects}
          selectedId={selectedId}
          includeArchived={includeArchived}
          onSelect={setSelectedId}
          onToggleIncludeArchived={setIncludeArchived}
          onCreate={createProject}
          onRename={renameProject}
          onToggleArchive={toggleArchive}
        />
        <section className="panel" aria-labelledby="keys-heading">
          <div className="panel-head">
            <h2 id="keys-heading">
              {selected ? (
                <>
                  {selected.name}의 키 {selectedArchived && <Badge tone="archived">보관됨</Badge>}
                </>
              ) : (
                '키'
              )}
            </h2>
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => setIssueOpen(true)}
              disabled={!selected || selectedArchived}
              title={selectedArchived ? '보관된 프로젝트에는 키를 발급할 수 없습니다. 먼저 보관을 해제하세요.' : undefined}
            >
              키 발급
            </button>
          </div>
          {selected ? <KeyTable keys={keys} onRevoke={setRevoking} /> : <p className="empty">왼쪽에서 프로젝트를 선택하거나 새로 만드세요.</p>}
        </section>
      </div>
      {selected && <IssueKeyDialog open={issueOpen} projectName={selected.name} onIssue={issueKey} onClose={closeIssue} />}
      <ConfirmDialog
        open={revoking !== null}
        title="키 폐기"
        message={revoking ? `키 ${revoking.keyPrefix}…(${revoking.name})를 폐기합니다. 이 키로 오는 요청은 즉시 401이 됩니다.` : ''}
        confirmLabel="폐기"
        danger
        busy={revokeBusy}
        onConfirm={() => void confirmRevoke()}
        onCancel={() => setRevoking(null)}
      />
    </div>
  )
}
