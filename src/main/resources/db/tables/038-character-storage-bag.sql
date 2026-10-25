-- Storage Bags (docs/STORAGE_BAG.md, changeset 47-character-storage-bag): each character's four Auto toggles (an
-- item a bag takes goes straight into it on pickup). The bag items themselves are inventoryitems rows of types 10-13
-- (ORE_BAG, SCROLL_BAG, CHAIR_BAG, MOUNT_BAG in client.inventory.ItemFactory) and need no table of their own. A row is
-- written by the character's first save after it opened the bag window; no row means every toggle off.
-- Rollback: 038-character-storage-bag-down.sql.

CREATE TABLE IF NOT EXISTS character_storage_bag (
    characterid INT        NOT NULL,
    auto_ore    TINYINT(1) NOT NULL DEFAULT 0,
    auto_scroll TINYINT(1) NOT NULL DEFAULT 0,
    auto_chair  TINYINT(1) NOT NULL DEFAULT 0,
    auto_mount  TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (characterid)
);
