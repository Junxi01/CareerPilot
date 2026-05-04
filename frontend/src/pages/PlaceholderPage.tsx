import React from 'react'

export function PlaceholderPage({ title, description }: { title: string; description?: string }) {
  return (
    <div>
      <h1 className="cp-page-title">{title}</h1>
      <p className="cp-muted">
        {description ?? 'This section is a placeholder. List and forms will connect to the API next.'}
      </p>
    </div>
  )
}
