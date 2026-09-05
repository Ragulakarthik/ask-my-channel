CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE channel (
    channel_id  VARCHAR(64) PRIMARY KEY,
    handle      VARCHAR(255) NOT NULL,
    title       VARCHAR(255),
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE video (
    video_id            VARCHAR(64) PRIMARY KEY,
    channel_id          VARCHAR(64) NOT NULL REFERENCES channel(channel_id),
    title               VARCHAR(1000),
    published_at        TIMESTAMP,
    duration_seconds     INTEGER
);
CREATE INDEX idx_video_channel_id ON video(channel_id);

CREATE TABLE ingest_job (
    job_id          UUID PRIMARY KEY,
    channel_id      VARCHAR(64) NOT NULL REFERENCES channel(channel_id),
    status          VARCHAR(20) NOT NULL,
    videos_total    INTEGER NOT NULL DEFAULT 0,
    videos_done     INTEGER NOT NULL DEFAULT 0,
    error_message   TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_ingest_job_channel_id ON ingest_job(channel_id);

CREATE TABLE chunk (
    id              BIGSERIAL PRIMARY KEY,
    channel_id      VARCHAR(64) NOT NULL REFERENCES channel(channel_id),
    video_id        VARCHAR(64) NOT NULL REFERENCES video(video_id),
    text            TEXT NOT NULL,
    start_seconds   DOUBLE PRECISION NOT NULL,
    embedding       vector(768) NOT NULL
);
CREATE INDEX idx_chunk_channel_id ON chunk(channel_id);
CREATE INDEX idx_chunk_embedding ON chunk USING hnsw (embedding vector_cosine_ops);
