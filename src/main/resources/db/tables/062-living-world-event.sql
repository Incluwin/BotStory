-- World events (docs/WORLD_SYSTEMS_PLAN.md N4, changeset 71-living-world-event).
--
-- living_world_event: one row an occurrence of a calendar event (WorldEvents, behind population.WORLD_EVENTS_ENABLED).
-- event_id is the events.yaml id; occurrence is its number, unique an event: the week_of (days since 1970-01-01 of its
-- Monday) of a weekly or fortnightly event, the day of a seasonal one, minus the epoch second of a GM's start. The unique
-- key is what keeps a restart from repeating an occurrence, and the row what keeps it from losing one. state: PLANNED,
-- RUNNING, DONE, CANCELLED (a GM, the switch) or MISSED (the server was down). site is where (an invasion's site id, a
-- festival's town). result is how it ended ("held", "overrun", "rolled held", ...). Times are UTC (SqlTime).
-- No foreign keys. Rollback: 062-living-world-event-down.sql.

CREATE TABLE IF NOT EXISTS living_world_event (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    event_id        VARCHAR(16)  NOT NULL,
    occurrence      INT          NOT NULL,
    starts_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ends_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    state           VARCHAR(12)  NOT NULL DEFAULT 'PLANNED',
    channel         INT          NOT NULL DEFAULT 0,
    site            VARCHAR(16)  NOT NULL DEFAULT '',
    result          VARCHAR(64)  NOT NULL DEFAULT '',
    PRIMARY KEY (id),
    UNIQUE KEY living_world_event_occurrence (event_id, occurrence),
    KEY living_world_event_open (state, starts_at)
);
