const STORAGE_KEY = 'careerpilot_auth_token'

export function getAuthToken(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

export function setAuthToken(token: string): void {
  localStorage.setItem(STORAGE_KEY, token)
}

export function clearAuthToken(): void {
  localStorage.removeItem(STORAGE_KEY)
}
