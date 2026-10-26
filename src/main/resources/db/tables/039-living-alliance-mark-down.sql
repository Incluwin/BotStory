-- Rollback for 039-living-alliance-mark.sql: the alliance check forgets its low spells and asked dates; alliances stay.
DROP TABLE IF EXISTS living_alliance_mark;
