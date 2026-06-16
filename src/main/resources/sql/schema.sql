-- name: create_whitelist_players
CREATE TABLE IF NOT EXISTS ${players_table} (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  player_key VARCHAR(${player_key_length}) NOT NULL UNIQUE,
  player_uuid VARCHAR(${player_uuid_length}),
  player_name VARCHAR(${player_name_length}) NOT NULL,
  code VARCHAR(${code_length}) NOT NULL,
  issue_date DATE NOT NULL,
  used_at DATETIME NOT NULL,
  source_server VARCHAR(${server_name_length}),
  last_seen_at DATETIME,
  INDEX idx_player_uuid (player_uuid),
  INDEX idx_player_name (player_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci

-- name: create_whitelist_logs
CREATE TABLE IF NOT EXISTS ${logs_table} (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  player_key VARCHAR(${player_key_length}) NOT NULL,
  player_name VARCHAR(${player_name_length}) NOT NULL,
  action VARCHAR(${action_length}) NOT NULL,
  code VARCHAR(${code_length}),
  server_name VARCHAR(${server_name_length}),
  ip VARCHAR(${ip_length}),
  message VARCHAR(${message_length}),
  created_at DATETIME NOT NULL,
  INDEX idx_player_key (player_key),
  INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
