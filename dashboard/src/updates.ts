/** A reducer that counts dispatches, used as a version that reload effects depend on. */
export function increment(count: number): number {
  return count + 1
}

/** A copy of [set] with [item] removed when present, added otherwise. */
export function toggled<T>(set: ReadonlySet<T>, item: T): Set<T> {
  const next = new Set(set)
  if (next.has(item)) next.delete(item)
  else next.add(item)
  return next
}
