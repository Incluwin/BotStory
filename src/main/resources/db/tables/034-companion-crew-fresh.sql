-- Keep my crew fresh (docs/BOT_MANAGEMENT_PLAN.md P5, decision 15 b, changeset 43-companion-crew-fresh): an owner's
-- opt-in, from the Crew window's "Keep my crew fresh" toggle. While it is on (1), a rested bench companion swaps in for
-- one out with the owner that is dead outside an instance, out of potions or too many levels off them (CrewRotation).
-- Off (0) by default. Ships behind living_world.ENABLED like 029. Rollback: 034-companion-crew-fresh-down.sql.

ALTER TABLE living_crew
    ADD COLUMN keep_fresh TINYINT(1) NOT NULL DEFAULT 0;
