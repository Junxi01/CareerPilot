/** Mirrors `com.careerpilot.targetcompanies.TargetCompanyDto` & requests. */
export type TargetCompanyDto = {
  id: number
  name: string
  careers_url: string
  keywords: string[]
  locations: string[]
  active: boolean
  notes?: string | null
}

export type CreateTargetCompanyRequest = {
  name: string
  careers_url: string
  keywords: string[]
  locations?: string[]
  active?: boolean
  notes?: string | null
}

export type PatchTargetCompanyRequest = {
  name?: string | null
  careers_url?: string | null
  keywords?: string[] | null
  locations?: string[] | null
  active?: boolean | null
  notes?: string | null
}
