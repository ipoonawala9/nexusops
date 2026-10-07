-- V0_2's schema-scoped ALTER DEFAULT PRIVILEGES could not remove the built-in PUBLIC EXECUTE default; only the global
-- form can. From now on functions created by the owner are not executable by PUBLIC (so not by nexusops_app), and the
-- functions that already exist lose it too. Trigger functions keep working: EXECUTE is checked when a trigger is
-- created, not when it fires.
ALTER DEFAULT PRIVILEGES FOR ROLE nexusops_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
