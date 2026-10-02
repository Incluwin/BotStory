-- PQ clear-time records (changeset 44-pq-run-pace): one row per companion party-quest run that got in, written by
-- PqPaceLedger as the run ends. The ledger reads the current epoch's newest rows at boot and learns the clear-time
-- model from them (AgentPqPaceCalibration.fit); the estimate itself is never learned from.
-- est_p10_s/est_p50_s/est_p90_s and est_records: the entry NPC's estimate of the lineup at the Yes, in seconds, and the
-- recorded runs behind it (NULL: none was made, the pace data was cold or living_world.COMPANION_PQ_CLEAR_TIME off).
-- An estimate that the lineup could not clear keeps est_records and leaves the three quantiles NULL, so the coverage
-- query below reads only timed estimates.
-- actual_s: from the run's start (the Yes) to its clear, NULL unless it cleared. started_at_ms and cleared_at_ms are
-- the run's own epoch milliseconds (cleared 0 unless it cleared), so spans read back exactly.
-- spans: JSON [{key, fromMs, toMs, work, complete}], one per segment of the quest's pace profile. members: JSON
-- [{id, human, run: {deaths, tappedOut, stage1Attacks, stage1Kills}, power: {...}}], each member's power when the run
-- began (absent while the pace data was cold) and how its run went. drawn: JSON {segment key: count}, the run's random
-- content as drawn (KPQ: coupons asked for, checks per puzzle stage). epoch: the pace profile's PACE_EPOCH (0: the
-- profile was cold). recorded_at is bound in a UTC session (SqlTime).
-- Coverage of the estimate, one read-only query:
--   SELECT AVG(actual_s BETWEEN est_p10_s AND est_p90_s), AVG(ABS(actual_s - est_p50_s)) FROM pq_run_pace
--   WHERE pq_key = 'kpq' AND epoch = ? AND outcome = 'cleared'
-- No foreign key, as companion_performance. Ships behind living_world.ENABLED like 029.
-- Rollback: 035-pq-run-pace-down.sql.

CREATE TABLE IF NOT EXISTS pq_run_pace (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    pq_key              VARCHAR(16)  NOT NULL,
    session_id          VARCHAR(64)  NOT NULL,
    leader_character_id INT          NOT NULL,
    party_size          TINYINT      NOT NULL,
    humans              TINYINT      NOT NULL,
    epoch               SMALLINT     NOT NULL,
    outcome             VARCHAR(12)  NOT NULL,
    est_p10_s           INT          NULL,
    est_p50_s           INT          NULL,
    est_p90_s           INT          NULL,
    est_records         SMALLINT     NULL,
    actual_s            INT          NULL,
    started_at_ms       BIGINT       NOT NULL,
    cleared_at_ms       BIGINT       NOT NULL DEFAULT 0,
    spans               TEXT         NOT NULL,
    members             TEXT         NOT NULL,
    drawn               TEXT         NOT NULL,
    recorded_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_pq_run_pace_session (session_id),
    INDEX idx_pq_run_pace_leader (leader_character_id),
    INDEX idx_pq_run_pace_epoch (pq_key, epoch, id)
);
