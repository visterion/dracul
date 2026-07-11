/** First ~maxLen chars of a pattern statement, cut at a word boundary, with ellipsis. */
export function patternTitle(statement: string, maxLen = 80): string {
  const text = statement.trim().replace(/\s+/g, ' ')
  if (text.length <= maxLen) return text
  const cut = text.slice(0, maxLen + 1)
  const lastSpace = cut.lastIndexOf(' ')
  const head = (lastSpace > 0 ? cut.slice(0, lastSpace) : cut.slice(0, maxLen))
    .replace(/[,;:.]+$/, '')
  return head + '…'
}
