-- name: is_whitelisted
SELECT 1 FROM ${players_table} WHERE player_key = ? LIMIT 1

-- name: find_by_key
SELECT * FROM ${players_table} WHERE player_key = ? LIMIT 1

-- name: find_by_name
SELECT * FROM ${players_table} WHERE player_name = ? ORDER BY used_at DESC LIMIT 1

-- name: upsert_player
INSERT INTO ${players_table}
  (player_key, player_uuid, player_name, code, issue_date, used_at, source_server, last_seen_at)
VALUES
  (?, ?, ?, ?, ?, ?, ?, ?)
ON DUPLICATE KEY UPDATE
  player_uuid = VALUES(player_uuid),
  player_name = VALUES(player_name),
  code = VALUES(code),
  issue_date = VALUES(issue_date),
  used_at = VALUES(used_at),
  source_server = VALUES(source_server),
  last_seen_at = VALUES(last_seen_at)

-- name: insert_manual_player
INSERT IGNORE INTO ${players_table}
  (player_key, player_uuid, player_name, code, issue_date, used_at, source_server, last_seen_at)
VALUES
  (?, ?, ?, NULL, ?, ?, ?, NULL)

-- name: remove_by_key
DELETE FROM ${players_table} WHERE player_key = ?

-- name: update_last_seen
UPDATE ${players_table} SET last_seen_at = ? WHERE player_key = ?

-- name: insert_log
INSERT INTO ${logs_table}
  (player_key, player_name, action, code, server_name, ip, message, created_at)
VALUES
  (?, ?, ?, ?, ?, ?, ?, ?)
