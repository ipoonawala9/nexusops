-- Functions created by the owner are not executable by everyone by default (Plan 1 review minor).
ALTER DEFAULT PRIVILEGES FOR ROLE nexusops_owner IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
