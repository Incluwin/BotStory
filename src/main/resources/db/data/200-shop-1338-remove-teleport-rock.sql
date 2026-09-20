-- Remove the Teleport Rock - Free Market (item 5041000) entry from
-- shop 1338 at 1,500,000 mesos. This row was originally inserted by
-- changeset 102 (db/data/102-shopitems-data.sql), so we can't just edit
-- 102 to remove the line — Liquibase rejects edits to applied changesets
-- with a checksum-mismatch error. Instead we run this DELETE as a fresh
-- changeset that runs idempotently after 102.
DELETE FROM shopitems WHERE shopid = 1338 AND itemid = 5041000;
