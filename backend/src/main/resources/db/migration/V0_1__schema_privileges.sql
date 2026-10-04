-- Runs as nexusops_owner. Every table/sequence the owner creates from now on is
-- usable (DML only) by the runtime role. Tables that need narrower grants
-- (e.g. append-only audit_events) REVOKE explicitly in their own migration.
ALTER DEFAULT PRIVILEGES FOR ROLE nexusops_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nexusops_app;
ALTER DEFAULT PRIVILEGES FOR ROLE nexusops_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO nexusops_app;
