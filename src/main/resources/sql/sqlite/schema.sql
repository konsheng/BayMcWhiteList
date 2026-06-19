-- name: create_whitelist_players
CREATE TABLE IF NOT EXISTS ${players_table} (
  player_uuid TEXT NOT NULL,
  player_name TEXT NOT NULL,
  code TEXT,
  issue_date TEXT NOT NULL,
  used_at TEXT NOT NULL,
  source_server TEXT,
  last_seen_at TEXT,
  PRIMARY KEY (player_uuid)
)

-- name: create_whitelist_players_name_index
CREATE INDEX IF NOT EXISTS ${players_name_index}
  ON ${players_table} (player_name)

-- name: create_whitelist_logs
CREATE TABLE IF NOT EXISTS ${logs_table} (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  player_uuid TEXT NOT NULL,
  player_name TEXT NOT NULL,
  action TEXT NOT NULL,
  code TEXT,
  server_name TEXT,
  ip TEXT,
  message TEXT,
  created_at TEXT NOT NULL
)

-- name: create_whitelist_logs_player_uuid_index
CREATE INDEX IF NOT EXISTS ${logs_player_uuid_index}
  ON ${logs_table} (player_uuid)

-- name: create_whitelist_logs_created_at_index
CREATE INDEX IF NOT EXISTS ${logs_created_at_index}
  ON ${logs_table} (created_at)
