# Architecture

## High-level design

```
Frontend (static HTML/JS, served by Spring Boot from src/main/resources/static)
        │ fetch() over HTTP
        ▼
Spring Boot Backend
  controller/  →  service/  →  repository/  →  client/

  POST /api/channels/{handle}/ingest   — kick off ingestion job
  GET  /api/channels/{handle}/status   — ingestion job status
  GET  /api/channels                   — list ingested channels
  POST /api/channels/{handle}/chat     — RAG query, cited answer
        │                                      │
        ▼                                      ▼
YoutubeClient                           GeminiClient
 (yt-dlp subprocess for listing;         (Spring WebClient → Generative
  direct HTTP GET of the json3           Language REST API: embedContent
  caption track for transcripts)         + generateContent)
        │                                      │
        ▼                                      ▼
IngestionService (fast: resolve channel) ──► IngestionWorker (@Async: full
                                              list → transcript → chunk → embed → persist)
        │
        ▼
PostgreSQL + pgvector (Docker Compose)
  channel / video / ingest_job / chunk(embedding vector(768), HNSW cosine index)
  multi-tenancy = a channel_id column + index on chunk, not separate DBs
```

## Key decisions

1. **Java 21 + Spring Boot 3, Maven, layered architecture** (`controller → service → repository/client`) —
   mirrors a familiar enterprise Spring pattern rather than a script.
2. **Multi-tenancy via a `channel_id` column**, not per-tenant databases — one Postgres
   instance serves every ingested channel; every chunk query is scoped `WHERE channel_id = ?`
   before the pgvector `ORDER BY embedding <=> ...`.
3. **PostgreSQL + pgvector** via `docker-compose.yml` (`pgvector/pgvector:pg16` image). The
   `vector` column is mapped with a custom Hibernate `UserType`
   (`entity/support/VectorType.java`) that reads/writes pgvector's text literal format
   directly via `PGobject`, avoiding any JDBC-driver-level type registration.
4. **No YouTube Data API key** — `YoutubeClient` shells out to the `yt-dlp` binary for listing
   and per-video metadata (`--dump-json`), then reads the pre-signed caption-track URL out of
   that metadata and fetches it directly over plain HTTP in YouTube's `json3` format. This
   sidesteps yt-dlp's own subtitle-downloader path, which returned HTTP 429 in testing, and
   avoids VTT's roll-up/duplicate-cue text format — `json3` gives discrete, non-overlapping,
   precisely-timed word events.
5. **Original-language detection**: YouTube auto-translates a channel's real auto-caption into
   every supported language on request. The *original* track is identified by a URL-parameter
   heuristic — every translated language's caption URLs contain `tlang=`, the one real
   original-language track's URLs do not. Verified against real (Telugu-language) channel data.
6. **Two providers via plain Spring `WebClient`s, not Spring AI's abstraction**, so the
   request/response mechanics stay visible and interview-defensible: Gemini
   (`gemini-embedding-001`, truncated + L2-normalized to 768 dimensions) for embeddings, and
   `AnswerGenerationService` for generation — tries Groq (`openai/gpt-oss-120b`) first,
   falling back to Gemini (`gemini-flash-latest`) if Groq throws. This came from hitting
   Gemini's `generateContent` free-tier daily quota (just 20 requests for the `gemini-3.8-flash`
   model behind the `-latest` alias) while `embedContent` sat on its own, unaffected quota —
   two independent free-tier generation paths meaningfully improve uptime at zero cost.
   `GeminiApiException`/`GroqApiException` both extend a common `LlmProviderException` so the
   fallback (and `GlobalExceptionHandler`) can catch either uniformly.
7. **Time-windowed chunking (~45s)** — `ChunkingService` is a pure function (no I/O), grouping
   consecutive transcript segments until the window is crossed. Each chunk carries
   `start_seconds` so a citation can link to `youtu.be/{videoId}?t={seconds}s`.
8. **Ingestion is async, split across two beans**: `IngestionService.startIngestion()` does a
   fast single-video lookup (`yt-dlp --playlist-items 1-1`) to resolve the channel's stable id
   and create the `channel`/`ingest_job` rows synchronously, then hands off to
   `IngestionWorker.runIngestion()` (a separate `@Component`, `@Async("ingestionExecutor")`) for
   the full listing + per-video transcript/chunk/embed/persist pass. It's a separate bean
   specifically because Spring's `@Async` only intercepts calls through the proxy — a
   self-invocation from within the same class would silently run synchronously.
9. **Handle normalization**: a channel can be ingested via a bare handle, an `@handle`, or a
   full URL with or without `/videos`. `HandleNormalizer` collapses all of these to one
   canonical `@handle` string used as the lookup key from chat requests back to the stable
   `channel_id`.
10. **Three content sources per video, not just the transcript**: `YoutubeClient.fetchVideoContent()`
    makes one `yt-dlp --dump-json --write-comments` call per video and pulls out the transcript,
    the video description, and up to 15 top comments (sorted by like count) from that single
    response — description/comments cost no extra request beyond what was already needed for
    the caption-track URL. Each resulting `Chunk` is tagged with a `ChunkSource`
    (`TRANSCRIPT`/`DESCRIPTION`/`COMMENT`); the chat prompt labels excerpts by source and is
    instructed to treat comments as viewer opinions, not confirmed facts from the creator. If
    the caption fetch itself fails (e.g. rate-limited), description/comments are still returned
    rather than losing the video entirely.
11. **Profile page for channel + API key configuration, backed by a single-row `app_settings`
    table**, not env vars alone. `SettingsService` resolves each value as "DB row if set, else
    the env-var-configured default" — an existing env-var-only deployment keeps working
    untouched, while `/profile.html` lets a deployer override any of the three fields (channel
    handle, Gemini key, Groq key) at runtime, taking effect on the very next request since
    `GeminiClient`/`GroqClient` resolve the key per-call rather than caching it at construction.
    Gated by a shared passphrase (`ProfileAuthService`, fails closed if unset) since the app has
    no login system and this page can repoint a live instance at a different channel. API keys
    are never sent back to the browser — `GET /api/profile` returns only booleans
    (`hasGeminiKey`/`hasGroqKey`); the UI's "leave blank to keep the existing key" convention
    means a save never needs to round-trip a secret that's already stored.
12. **Streamed answers via Server-Sent Events**, sharing the same retrieval path as the
    non-streaming endpoint (`ChatService.retrieveForChannel` is used by both `ask()` and
    `askStreaming()`). `GroqClient.generateStream()` consumes Groq's OpenAI-compatible
    chat-completion-chunk SSE format directly via `WebClient.bodyToFlux(ServerSentEvent.class)`,
    extracting only `delta.content` — reasoning models like `gpt-oss` stream a separate
    `delta.reasoning` field first, which is simply never read. `POST /{handle}/chat/stream`
    bridges the resulting `Flux<String>` to a Spring MVC `SseEmitter`: a `citations` event first
    (retrieval already finished), then one `token` event per chunk, forcing
    `MediaType.APPLICATION_JSON` on each token's data so multi-line/quote-containing text is
    escaped safely. If Groq's stream errors — including immediately, before any token —
    `AnswerGenerationService.generateStream()` falls back to Gemini's blocking `generate()`,
    surfaced as a single `token` event rather than a real stream (a degraded but working answer
    beats none). The frontend consumes this via `fetch()` + `response.body.getReader()` rather
    than the native `EventSource` API, since `EventSource` only supports `GET` with no request
    body — not viable here given the request needs a JSON body (question + history).
13. **`docker-compose.yml` runs the whole stack**, not just Postgres — an `app` service builds
    from the repo's own multi-stage `Dockerfile` (JRE + `yt-dlp` bundled) and depends on
    `postgres` via a `pg_isready` healthcheck, so `docker compose up -d --build` is the entire
    "clone this repo" story with zero local Java/Maven/`yt-dlp` install required. Running the app
    locally against just the `postgres` service (`docker compose up -d postgres` +
    `mvn spring-boot:run`) still works unchanged for active development. CI
    (`.github/workflows/ci.yml`) runs `mvn -B verify` on every push/PR — no service containers
    needed since all 21 tests are pure Mockito unit tests with no Spring context or real DB.

## Data model

- `channel(channel_id PK, handle, title, created_at)`
- `video(video_id PK, channel_id FK, title, published_at, duration_seconds)`
- `ingest_job(job_id PK, channel_id FK, status, videos_total, videos_done, error_message, created_at, updated_at)`
- `chunk(id PK, channel_id FK, video_id FK, text, start_seconds, source, embedding vector(768))`,
  indexed with `USING hnsw (embedding vector_cosine_ops)` plus a plain index on `channel_id`.
  `source` is `TRANSCRIPT` / `DESCRIPTION` / `COMMENT` (added in `V2__add_chunk_source.sql`,
  defaulting existing rows to `TRANSCRIPT`)
- `app_settings(id PK fixed to 1, channel_handle, gemini_api_key, groq_api_key, updated_at)` —
  single row enforced via a `CHECK (id = 1)` constraint (added in `V3__add_app_settings.sql`)

## Error handling

- Unknown channel/job → `404` (`NoSuchElementException` → `GlobalExceptionHandler`)
- yt-dlp missing, a video with no captions, or a caption-fetch failure → `YoutubeClientException`
  → `502`; individual video failures are logged and skipped rather than failing the whole job
- Gemini/Groq rate limiting → retry with backoff inside each client, then a provider-specific
  exception; generation additionally falls back Groq → Gemini before giving up → `503`
  (`LlmProviderException`)

## Testing

- `ChunkingServiceTest` — pure unit tests, fabricated transcript segments, no network/DB
- `ChatServiceTest` — Mockito-mocked `ChunkRepository`/`GeminiClient`/`AnswerGenerationService`/
  `ChannelRepository`/`VideoRepository`, asserts prompt assembly, citation URL formatting, and
  (for `askStreaming()`) that citations are returned alongside a working `Flux` of answer chunks
- `AnswerGenerationServiceTest` — Mockito-mocked `GroqClient`/`GeminiClient`, asserts the
  fallback for both `generate()` and `generateStream()`: Groq success skips Gemini entirely,
  Groq failure falls back to Gemini (a full answer surfaced as one chunk for the streaming
  case), both failing raises one combined `LlmProviderException`
- `SettingsServiceTest` — DB value wins when present, falls back to the env-var default when
  blank/absent, and partial updates leave unspecified fields untouched
