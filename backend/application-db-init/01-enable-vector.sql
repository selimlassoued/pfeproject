-- Runs once when application-db initializes a fresh data volume.
-- Enables pgvector's vector extension so the skill_catalog_entry table can
-- hold the embedding column (added by Hibernate ddl-auto on app startup).
CREATE EXTENSION IF NOT EXISTS vector;
