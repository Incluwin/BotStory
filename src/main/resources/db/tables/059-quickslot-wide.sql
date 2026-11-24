-- The 26-slot quickslot bar (BotStory-Kaentake enlarge_quickslot): keys for slots 9 to 26, one byte each, beside the
-- 8 keys of the stock bar in keymap. NULL until a client with the 26-slot bar saves its keys.
ALTER TABLE quickslotkeymapped ADD COLUMN wide_tail VARBINARY(18) NULL;
