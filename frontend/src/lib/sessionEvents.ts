/** Dispatched when an API call returns 401 so auth state can sync (see `api/client.ts`). */
export const SESSION_EXPIRED_EVENT = 'careerpilot:session-expired'

export function emitSessionExpired(): void {
  window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT))
}
