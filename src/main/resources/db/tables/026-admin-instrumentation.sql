-- ADMIN_DASHBOARD_PLAN.md §12 Phase D instrumentation.
-- These tables accumulate the data Phase D charts will render. Writes start
-- immediately (Java side) so there's a backlog of real data by the time the
-- visualization pages land. Auto-prune policy is §14 open; for now no rows
-- are ever deleted.

CREATE TABLE IF NOT EXISTS admin_login_events
(
    id           INT UNSIGNED  NOT NULL AUTO_INCREMENT,
    ts           TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    event_type   VARCHAR(16)   NOT NULL,   -- 'login' | 'logout'
    account_id   INT           DEFAULT NULL,
    character_id INT           DEFAULT NULL,
    world_id     INT           DEFAULT NULL,
    channel_id   INT           DEFAULT NULL,
    ip           VARCHAR(45)   DEFAULT NULL,
    hwid         VARCHAR(64)   DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_admin_login_events_ts (ts),
    KEY idx_admin_login_events_account (account_id, ts),
    KEY idx_admin_login_events_character (character_id, ts)
);

CREATE TABLE IF NOT EXISTS admin_player_samples
(
    id           INT UNSIGNED  NOT NULL AUTO_INCREMENT,
    ts           TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    world_id     INT           NOT NULL,
    channel_id   INT           NOT NULL,
    player_count INT           NOT NULL,
    PRIMARY KEY (id),
    KEY idx_admin_player_samples_ts (ts),
    KEY idx_admin_player_samples_world_ts (world_id, channel_id, ts)
);

-- Written every 5 min per map that has at least one player on it — that
-- pruning keeps the row volume proportional to *active* content, not the
-- full map catalog (~2000 maps).
CREATE TABLE IF NOT EXISTS admin_map_presence_samples
(
    id         INT UNSIGNED  NOT NULL AUTO_INCREMENT,
    ts         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    world_id   INT           NOT NULL,
    channel_id INT           NOT NULL,
    map_id     INT           NOT NULL,
    count      INT           NOT NULL,
    PRIMARY KEY (id),
    KEY idx_admin_map_presence_ts (ts),
    KEY idx_admin_map_presence_map_ts (map_id, ts)
);
