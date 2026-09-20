-- Recovery: if any bot account has actually logged in, the login guard has been bypassed.
-- CRITICAL severity — do not ignore.

-- Inspect which bots attempted login:
SELECT ale.account_id, ale.character_id, ale.ts, ale.ip
FROM admin_login_events ale
JOIN characters c ON c.accountid = ale.account_id
WHERE ale.event_type = 'login' AND c.bot_flag = 1
ORDER BY ale.ts DESC;

-- Remediation:
-- 1. Revoke access on affected accounts:
-- UPDATE accounts SET banned = 1, bot_flag = 1
-- WHERE id IN (SELECT DISTINCT account_id FROM admin_login_events ale
--              JOIN characters c ON c.accountid = ale.account_id
--              WHERE event_type = 'login' AND c.bot_flag = 1);
--
-- 2. Investigate Client.login path — the bot_flag check must be returning false.
-- See client/Client.java login() for the guard.
