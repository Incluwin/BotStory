-- Rollback of 044-rival-crew.sql (changeset 53-rival-crew). The crews' guilds and circles stay as ordinary rows.
DROP TABLE IF EXISTS living_rival;
DROP TABLE IF EXISTS living_clear_member;
DROP TABLE IF EXISTS living_clear;
