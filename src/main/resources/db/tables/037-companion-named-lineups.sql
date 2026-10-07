-- Named lineups (docs/BOT_MANAGEMENT_PLAN.md P9, changeset 46-companion-named-lineups): the crews an owner keeps under
-- the three names the Crew window offers (HUNTING, PQ, BOSS), one row per place, 1 = first, 5 places at most. Saved by
-- "Remember this crew as..." from whoever is out with the owner, in party order; calling one swaps the companions out
-- now for its own. The usual lineup stays in living_roster.lineup_slot. No foreign key, as living_roster: a released
-- companion keeps its place and is passed over when the lineup is called. Ships behind living_world.ENABLED.
-- Rollback: 037-companion-named-lineups-down.sql.

CREATE TABLE IF NOT EXISTS companion_named_lineup (
    owner_character_id INT          NOT NULL,
    lineup             VARCHAR(8)   NOT NULL,
    slot               TINYINT      NOT NULL,
    persona_id         VARCHAR(64)  NOT NULL,
    saved_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_character_id, lineup, slot)
);
