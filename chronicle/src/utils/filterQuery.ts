/** Chronicle filter <-> URL query params: filter=all|high, class=<AnomalyClass>. */
export function filterToQuery(filter: string): Record<string, string> {
  if (filter === 'all') return {}
  if (filter === 'high') return { filter: 'high' }
  return { class: filter }
}

export function queryToFilter(query: Record<string, unknown>): string {
  if (typeof query.class === 'string' && query.class !== '') return query.class
  if (query.filter === 'high') return 'high'
  return 'all'
}
