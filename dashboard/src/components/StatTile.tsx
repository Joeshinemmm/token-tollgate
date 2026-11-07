interface StatTileProps {
  label: string
  value: string
  detail?: string
  warning?: string | null
}

/** A headline number with its label and one quiet line under it. */
export function StatTile({ label, value, detail, warning }: StatTileProps) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {detail && <div className="stat-detail">{detail}</div>}
      {warning && (
        <div className="stat-warn" role="status">
          {warning}
        </div>
      )}
    </div>
  )
}
