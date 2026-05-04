import { apiGet, apiPost } from './client'
import type { AuthPayload, LoginRequest, MeResponse, RegisterRequest } from '../types/auth'

export function register(req: RegisterRequest): Promise<AuthPayload> {
  return apiPost<AuthPayload>('/api/auth/register', req, true)
}

export function login(req: LoginRequest): Promise<AuthPayload> {
  return apiPost<AuthPayload>('/api/auth/login', req, true)
}

export function fetchMe(): Promise<MeResponse> {
  return apiGet<MeResponse>('/api/me')
}
