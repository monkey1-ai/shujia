CREATE TABLE IF NOT EXISTS chat_message (
    id         BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(64) UNIQUE NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    role       VARCHAR(16) NOT NULL,
    content    TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );

CREATE INDEX IF NOT EXISTS idx_chat_message_session
    ON chat_message(session_id, id);

CREATE TABLE IF NOT EXISTS doc_index (
    file_name    VARCHAR(255) PRIMARY KEY,
    content_hash VARCHAR(64)  NOT NULL,
    chunk_count  INT          NOT NULL,
    loaded_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
    );
CREATE TABLE IF NOT EXISTS chat_session (
    session_id  VARCHAR(64) PRIMARY KEY,
    title       VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
    );

CREATE INDEX IF NOT EXISTS idx_chat_session_updated
    ON chat_session(updated_at DESC);

