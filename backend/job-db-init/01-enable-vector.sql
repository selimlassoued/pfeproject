-- Runs once when the job-db container initializes a fresh data volume.
-- pgvector's vector extension powers the `embedding VECTOR(768)` column on
-- the job_offer table (added by Hibernate ddl-auto on Spring Boot startup).
CREATE EXTENSION IF NOT EXISTS vector;
