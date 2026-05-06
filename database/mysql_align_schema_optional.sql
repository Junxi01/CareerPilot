-- Idempotent column adds: only ALTER when the column is missing.
-- Matches careerpilot-local/database/schema.sql (users, target_companies, job_leads).
-- Safe to run multiple times.

DROP PROCEDURE IF EXISTS cp_add_column_if_missing;
DELIMITER $$
CREATE PROCEDURE cp_add_column_if_missing(
  IN p_table VARCHAR(64),
  IN p_column VARCHAR(64),
  IN p_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table
      AND COLUMN_NAME = p_column
    LIMIT 1
  ) THEN
    SET @cp_sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
    PREPARE cp_stmt FROM @cp_sql;
    EXECUTE cp_stmt;
    DEALLOCATE PREPARE cp_stmt;
  END IF;
END$$
DELIMITER ;

-- users
CALL cp_add_column_if_missing('users', 'password_hash', 'VARCHAR(255) NULL');

-- target_companies
CALL cp_add_column_if_missing('target_companies', 'notes', 'TEXT NULL');

-- job_leads (full parity with schema.sql column set)
CALL cp_add_column_if_missing('job_leads', 'location', 'VARCHAR(255) NULL');
CALL cp_add_column_if_missing('job_leads', 'location_raw', 'VARCHAR(255) NULL');
CALL cp_add_column_if_missing('job_leads', 'raw_description', 'MEDIUMTEXT NULL');
CALL cp_add_column_if_missing('job_leads', 'discovered_at', 'TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP');
CALL cp_add_column_if_missing('job_leads', 'match_score', 'DECIMAL(5,2) NULL');
CALL cp_add_column_if_missing('job_leads', 'saved_to_applications', 'TINYINT(1) NOT NULL DEFAULT 0');
CALL cp_add_column_if_missing('job_leads', 'status', 'VARCHAR(32) NOT NULL DEFAULT ''new''');
CALL cp_add_column_if_missing('job_leads', 'source', 'VARCHAR(64) NOT NULL DEFAULT ''career_page''');
CALL cp_add_column_if_missing('job_leads', 'matched_keywords_json', 'JSON NULL');
CALL cp_add_column_if_missing('job_leads', 'raw_json', 'JSON NULL');
CALL cp_add_column_if_missing('job_leads', 'created_at', 'TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP');
CALL cp_add_column_if_missing('job_leads', 'updated_at', 'TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');

-- reminders (dashboard + ReminderRepository expect these names)
CALL cp_add_column_if_missing('reminders', 'application_id', 'BIGINT UNSIGNED NULL');
CALL cp_add_column_if_missing('reminders', 'reminder_type', 'VARCHAR(32) NOT NULL DEFAULT ''CUSTOM''');
CALL cp_add_column_if_missing('reminders', 'due_at', 'TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP');
CALL cp_add_column_if_missing('reminders', 'message', 'VARCHAR(512) NULL');
CALL cp_add_column_if_missing('reminders', 'done', 'TINYINT(1) NOT NULL DEFAULT 0');

DROP PROCEDURE IF EXISTS cp_add_column_if_missing;
