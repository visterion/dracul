import type { ApiClient } from './ApiClient'
import { MockApiClient } from './MockApiClient'
import { HttpApiClient } from './HttpApiClient'

let _client: ApiClient | null = null

export function useApi(): ApiClient {
  if (!_client) {
    _client =
      import.meta.env.VITE_MOCK === 'true'
        ? new MockApiClient()
        : new HttpApiClient(import.meta.env.VITE_API_BASE ?? '')
  }
  return _client
}
