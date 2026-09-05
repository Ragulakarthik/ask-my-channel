CREATE TABLE app_settings (
    id              SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    channel_handle  VARCHAR(255),
    gemini_api_key  VARCHAR(255),
    groq_api_key    VARCHAR(255),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
