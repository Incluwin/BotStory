-- Companion traits (docs/WORLD_SYSTEMS_PLAN.md N9.1): how low one companion's HP went in a party-quest or boss run,
-- for its traits (server.living.roster.Traits). min_hp_percent = its lowest HP in the run as a share 0-100 of its
-- maximum (0 when it died in it); close_calls = how often it fell under 20 % and came back. Both NULL on rows written
-- before changeset 66 or while living_world.TRAITS_ENABLED is off (nothing measured them). Rollback:
-- 057-companion-traits-down.sql.
ALTER TABLE companion_performance
    ADD COLUMN min_hp_percent TINYINT  NULL,
    ADD COLUMN close_calls    SMALLINT NULL;
