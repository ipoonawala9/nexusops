-- Plan 4 final fix (I1): consecutive wrong TOTP codes after a correct password. At 10 the account is disabled
-- (PlatformUser.MAX_FAILED_TOTP); a correct code or re-enabling the account resets the count.
ALTER TABLE platform_users ADD COLUMN failed_totp_attempts integer NOT NULL DEFAULT 0;
