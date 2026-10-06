-- E2E-only platform operator for the LOCAL compose stack. The secret is bound to the committed, non-secret local
-- PLATFORM_TOTP_KEY, so it is useless against any environment with a real key. Idempotent: re-running resets state.
SET app.platform_access = 'on';
INSERT INTO platform_users (id, email, password_hash, totp_secret_enc, totp_last_step, failed_totp_attempts,
                            role, status, token_version, created_at, updated_at)
VALUES ('01930000-0000-7000-8000-0000000e2e01', 'e2e-ops@nexusops.test', '$argon2id$v=19$m=16384,t=2,p=1$meIK3AwHB7/okTox8mhzIw$LcgT+M5mzAsWD/+jtCrBftRjgpJcd4FKs8v2oHWpCfs', 'v1:D1CdoZKIwMNwbUATD/BduHS0x8EcZjP8vQkK0cdCSz4qWISfkr1R7faAIInxn95h', 0, 0,
        'PLATFORM_ADMIN', 'ACTIVE', 0, now(), now())
ON CONFLICT (id) DO UPDATE SET
    password_hash = EXCLUDED.password_hash,
    totp_secret_enc = EXCLUDED.totp_secret_enc,
    totp_last_step = 0,
    failed_totp_attempts = 0,
    role = 'PLATFORM_ADMIN',
    status = 'ACTIVE',
    updated_at = now();
