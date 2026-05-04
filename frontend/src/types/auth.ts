/** Mirrors `com.careerpilot.repo.PublicUser` / auth payloads. */
export type PublicUser = {
  id: number
  email: string
  displayName?: string | null
}

export type RegisterRequest = {
  email: string
  password: string
  displayName?: string | null
}

export type LoginRequest = {
  email: string
  password: string
}

export type AuthPayload = {
  token: string
  user: PublicUser
}

export type MeResponse = {
  user: PublicUser
}
