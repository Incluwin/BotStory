-- Repack maps (docs/REPACK_IMPORT_PLAN.md, changeset 48-account-client-files): which game files an account's client
-- has, so server.mods.RepackMaps only sends Community Repack clients into the maps the Cosmic WZ files lack. A row is
-- written when the player answers an entry NPC or @gamefiles, a GM uses !gamefiles, a client closes before a Repack
-- map loaded (cosmic, source crash), or Kaentake.dll reports repack_wz (source client). No row = not known yet.
-- Rollback: 039-account-client-files-down.sql.

CREATE TABLE IF NOT EXISTS account_client_files (
    accountid INT        NOT NULL,
    files     VARCHAR(8) NOT NULL,
    source    VARCHAR(8) NOT NULL,
    updated   TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (accountid)
);
