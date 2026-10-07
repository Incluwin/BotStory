-- Rollback for 037-companion-named-lineups.sql: the named lineups are forgotten; the usual lineup stays.
DROP TABLE IF EXISTS companion_named_lineup;
