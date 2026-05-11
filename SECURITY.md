# Security policy

## No secrets in the repository

- **Never commit** `.env`, API keys, JWT secrets, database passwords, or tokens.
- The repo ships **`.env.example`** only: copy it to `.env` locally and fill in real values (**`SECURITY.md`** and **`.gitignore`** keep `.env` out of Git).
- AI provider keys (**`AI_API_KEY`**, **`GEMINI_API_KEY`**, etc.) and script credentials (**`SCRIPTS_API_TOKEN`**, **`SCRIPTS_EMAIL`** / **`SCRIPTS_PASSWORD`**) are read at runtime from your environment or `.env` on your machine only.
- If you paste secrets into issues, pull requests, or discussions, **rotate them immediately** and treat them as compromised.

## Supported versions

This is a **self-hosted, local-first** project. Security expectations:

- Run it on networks and machines you control.
- Keep dependencies updated (`./gradlew dependencyUpdates` / `npm audit` / `pip` pins) for your deployment.

## Reporting a vulnerability

If you believe you have found a security issue in **this repository** (not third-party services):

1. **Do not** open a public GitHub issue with exploit details.
2. Contact the maintainers privately (e.g. GitHub **Security → Private vulnerability report** if enabled, or email listed on the maintainer profile).
3. Include: affected component (backend / frontend / scripts), reproduction steps, and impact.

We will treat good-faith reports seriously. This is a volunteer OSS project; response times are best-effort.

## Out of scope (by design)

- Scraping or automating **LinkedIn, Indeed, Glassdoor**, or other sites that forbid it or require login for the data you want.
- Storing or transmitting user secrets in application logs in production (configure log redaction for your environment).

For **AI and data handling**, see **`docs/ai-setup.md`**.
