-- Runs once when application-db initializes a fresh data volume.
-- Enables PostgreSQL's fuzzystrmatch extension so the skill catalog can use
-- levenshtein(a, b) for lexical typo detection. Catches "doker" -> "docker"
-- (1 edit), "phyton" -> "python" (2 edits), and similar cases where the
-- embedding-based dedup cannot help because cosine similarity is too low for
-- character-level typos.
CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;
