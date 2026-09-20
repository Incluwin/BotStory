-- Rebalances NX Card drop rates on existing deployments whose 151 changeset already
-- ran with the old values. Fresh installs already have the new rates from 151.

UPDATE drop_data_global SET chance = 3000 WHERE itemid = 4031865 AND chance = 2000;
UPDATE drop_data_global SET chance = 1500 WHERE itemid = 4031866 AND chance = 500;
