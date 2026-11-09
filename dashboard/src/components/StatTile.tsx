interface StatTileProps {
  label: string
  value: string
  detail?: string
  warning?: string | null
  /** The one figure the screen leads with: set large, with its label under it instead of beside it. */
  lead?: boolean
}

/** A figure with its label and one quiet line under it. */
export function StatTile({ label, value, detail, warning, lead }: StatTileProps) {
  return (
    <div className={lead ? 'stat stat-lead' : 'stat'}>
      {lead ? (
        <>
          <div className="stat-value">{value}</div>
          <div className="stat-label">{label}</div>
        </>
      ) : (
        <>
          <div className="stat-label">{label}</div>
          <div className="stat-value">{value}</div>
        </>
      )}
      {detail && <div className="stat-detail">{detail}</div>}
      {warning && (
        <div className="stat-warn" role="status">
          {warning}
        </div>
      )}
    </div>
  )
}
