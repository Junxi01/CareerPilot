import React from 'react'
import { ErrorAlert } from './ErrorAlert'

export function ErrorMessage({ message, onDismiss }: { message: string; onDismiss?: () => void }) {
  return <ErrorAlert message={message} onDismiss={onDismiss} />
}
