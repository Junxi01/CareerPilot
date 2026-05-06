import React from 'react'
import { LoadingBlock } from './LoadingBlock'

export function LoadingState({ label }: { label?: string }) {
  return <LoadingBlock label={label ?? 'Loading…'} />
}
