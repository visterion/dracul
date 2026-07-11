/** Error carrying the HTTP status so views can map statuses to i18n messages. */
export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message)
    this.name = 'ApiError'
  }
}
