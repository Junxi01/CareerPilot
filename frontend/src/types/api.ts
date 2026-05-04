/** Mirrors `com.careerpilot.api.ApiModels` (Kotlin). */
export type ApiError = {
  code: string
  message: string
}

export type ApiResponse<T> = {
  success: boolean
  data?: T
  error?: ApiError
}
