import { ref } from 'vue'
import { useApi } from '../api'

// Module-level singleton: the current user's email, fetched once and shared.
const email = ref<string>('')
let started = false

export function useMe() {
  if (!started) {
    started = true
    useApi().getMe().then(m => { email.value = m.email }).catch(() => { /* ignore */ })
  }
  return email
}
