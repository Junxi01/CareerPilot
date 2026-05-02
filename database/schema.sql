-- CareerPilot Local — MySQL schema (Day 3)
--
-- Loaded by Docker Compose on first MySQL init (empty data volume).
-- IMPORTANT: Keep MYSQL_DATABASE (docker) and DB_NAME (app) aligned with the DB used here.

CREATE TABLE IF NOT EXISTS users (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  email VARCHAR(255) NOT NULL,
  password_hash VARCHAR(255) NULL,
  display_name VARCHAR(190) NULL,
  timezone VARCHAR(64) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS target_companies (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(190) NOT NULL,
  careers_page_url VARCHAR(2048) NOT NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  locations_json JSON NULL,
  role_keywords_json JSON NULL,
  tech_keywords_json JSON NULL,
  notes TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_target_companies_user_id (user_id),
  KEY ix_target_companies_active (active),
  CONSTRAINT fk_target_companies_user
    FOREIGN KEY (user_id) REFERENCES users (id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS job_leads (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  company_id BIGINT UNSIGNED NOT NULL,
  title VARCHAR(255) NOT NULL,
  url VARCHAR(2048) NOT NULL,
  location VARCHAR(255) NULL,
  location_raw VARCHAR(255) NULL,
  raw_description MEDIUMTEXT NULL,
  discovered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  match_score DECIMAL(5,2) NULL,
  saved_to_applications TINYINT(1) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'new',
  source VARCHAR(64) NOT NULL DEFAULT 'career_page',
  matched_keywords_json JSON NULL,
  raw_json JSON NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_job_leads_url (url(190)),
  KEY ix_job_leads_company_id (company_id),
  KEY ix_job_leads_discovered_at (discovered_at),
  KEY ix_job_leads_saved (saved_to_applications),
  KEY ix_job_leads_match_score (match_score),
  CONSTRAINT fk_job_leads_company
    FOREIGN KEY (company_id) REFERENCES target_companies (id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS applications (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  job_lead_id BIGINT UNSIGNED NULL,
  status VARCHAR(32) NOT NULL,
  applied_at DATE NULL,
  next_follow_up_date DATE NULL,
  notes TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_applications_user_id (user_id),
  KEY ix_applications_company_id (company_id),
  KEY ix_applications_job_lead_id (job_lead_id),
  KEY ix_applications_status (status),
  CONSTRAINT fk_applications_user
    FOREIGN KEY (user_id) REFERENCES users (id)
    ON DELETE CASCADE,
  CONSTRAINT fk_applications_company
    FOREIGN KEY (company_id) REFERENCES target_companies (id)
    ON DELETE CASCADE,
  CONSTRAINT fk_applications_job_lead
    FOREIGN KEY (job_lead_id) REFERENCES job_leads (id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS interviews (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  application_id BIGINT UNSIGNED NOT NULL,
  round_name VARCHAR(190) NULL,
  scheduled_at TIMESTAMP NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'scheduled',
  notes TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_interviews_application_id (application_id),
  KEY ix_interviews_scheduled_at (scheduled_at),
  CONSTRAINT fk_interviews_application
    FOREIGN KEY (application_id) REFERENCES applications (id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ai_interview_plans (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  application_id BIGINT UNSIGNED NOT NULL,
  provider_mode VARCHAR(16) NOT NULL DEFAULT 'mock',
  prompt_json JSON NULL,
  plan_json JSON NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_ai_interview_plans_application_id (application_id),
  CONSTRAINT fk_ai_interview_plans_application
    FOREIGN KEY (application_id) REFERENCES applications (id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS prep_tasks (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  ai_interview_plan_id BIGINT UNSIGNED NOT NULL,
  label VARCHAR(255) NOT NULL,
  description TEXT NULL,
  due_date DATE NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'todo',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_prep_tasks_ai_interview_plan_id (ai_interview_plan_id),
  KEY ix_prep_tasks_due_date (due_date),
  CONSTRAINT fk_prep_tasks_ai_interview_plan
    FOREIGN KEY (ai_interview_plan_id) REFERENCES ai_interview_plans (id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS reminders (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  due_date DATE NOT NULL,
  message VARCHAR(512) NOT NULL,
  done TINYINT(1) NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_reminders_user_id (user_id),
  KEY ix_reminders_due_date (due_date),
  CONSTRAINT fk_reminders_user
    FOREIGN KEY (user_id) REFERENCES users (id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS app_settings (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NULL,
  setting_key VARCHAR(190) NOT NULL,
  setting_json JSON NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_app_settings_user_key (user_id, setting_key),
  KEY ix_app_settings_key (setting_key),
  CONSTRAINT fk_app_settings_user
    FOREIGN KEY (user_id) REFERENCES users (id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
