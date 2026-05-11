-- CareerPilot Local — seed data (Day 3)
-- Loaded by Docker Compose on first MySQL init (empty data volume).

-- This seed file is safe to re-run manually.
START TRANSACTION;

-- Demo user (upsert). Password: demo12345 — for local demo only; never use in production.
-- Hash: bcrypt cost 12 (compatible with backend PasswordHasher).
INSERT INTO users (
  email,
  display_name,
  timezone,
  password_hash
)
VALUES (
  'demo@careerpilot.local',
  'Demo User',
  'America/Los_Angeles',
  '$2b$12$C6T4Npn333L3.RMhM0N16eoJ2TA30JNj0wXkfUS5XRl4OzC68wgcS'
)
ON DUPLICATE KEY UPDATE
  display_name = VALUES(display_name),
  timezone = VALUES(timezone),
  password_hash = VALUES(password_hash),
  id = LAST_INSERT_ID(id);

SET @demo_user_id = LAST_INSERT_ID();

-- Two target companies
-- Recreate demo companies and their leads to avoid duplicates on repeated imports.
DELETE jl
  FROM job_leads jl
  JOIN target_companies tc ON tc.id = jl.company_id
 WHERE tc.user_id = @demo_user_id
   AND tc.name IN ('Acme Corp', 'Globex');

DELETE FROM target_companies
 WHERE user_id = @demo_user_id
   AND name IN ('Acme Corp', 'Globex');

INSERT INTO target_companies (
  user_id,
  name,
  careers_page_url,
  active,
  locations_json,
  role_keywords_json,
  tech_keywords_json
)
VALUES
(
  @demo_user_id,
  'Acme Corp',
  'https://example.com/acme/careers',
  1,
  JSON_ARRAY('San Francisco, CA', 'Remote'),
  JSON_ARRAY('backend', 'platform'),
  JSON_ARRAY('kotlin', 'mysql', 'ktor')
),
(
  @demo_user_id,
  'Globex',
  'https://example.com/globex/jobs',
  1,
  JSON_ARRAY('New York, NY'),
  JSON_ARRAY('full stack', 'react'),
  JSON_ARRAY('typescript', 'react', 'mysql')
);

-- Capture company IDs (assumes clean init; if you add more seeds later, adjust accordingly)
SET @acme_company_id = (SELECT id FROM target_companies WHERE user_id = @demo_user_id AND name = 'Acme Corp' LIMIT 1);
SET @globex_company_id = (SELECT id FROM target_companies WHERE user_id = @demo_user_id AND name = 'Globex' LIMIT 1);

-- Sample job leads
INSERT INTO job_leads (
  company_id,
  title,
  url,
  location,
  discovered_at,
  status,
  source,
  matched_keywords_json,
  raw_json
)
VALUES
(
  @acme_company_id,
  'Backend Engineer, Platform',
  'https://example.com/acme/careers/backend-platform-1',
  'Remote',
  NOW(),
  'new',
  'career_page',
  JSON_ARRAY('kotlin', 'mysql', 'platform'),
  JSON_OBJECT('source', 'seed')
),
(
  @acme_company_id,
  'Software Engineer, Data',
  'https://example.com/acme/careers/data-2',
  'San Francisco, CA',
  NOW(),
  'new',
  'career_page',
  JSON_ARRAY('sql', 'pipelines'),
  JSON_OBJECT('source', 'seed')
),
(
  @globex_company_id,
  'Full Stack Engineer',
  'https://example.com/globex/jobs/fullstack-3',
  'New York, NY',
  NOW(),
  'new',
  'career_page',
  JSON_ARRAY('react', 'typescript'),
  JSON_OBJECT('source', 'seed')
);

COMMIT;
