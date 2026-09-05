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
6. **Gemini via a plain Spring `WebClient`** hitting the REST API directly
   (`gemini-embedding-001` truncated + L2-normalized to 768 dimensions; `gemini-flash-latest`
   for generation) rather than Spring AI's abstraction, so the request/response mechanics stay
   visible and interview-defensible.
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

## Data model

- `channel(channel_id PK, handle, title, created_at)`
- `video(video_id PK, channel_id FK, title, published_at, duration_seconds)`
- `ingest_job(job_id PK, channel_id FK, status, videos_total, videos_done, error_message, created_at, updated_at)`
- `chunk(id PK, channel_id FK, video_id FK, text, start_seconds, embedding vector(768))`,
  indexed with `USING hnsw (embedding vector_cosine_ops)` plus a plain index on `channel_id`

## Error handling

- Unknown channel/job → `404` (`NoSuchElementException` → `GlobalExceptionHandler`)
- yt-dlp missing, a video with no captions, or a caption-fetch failure → `YoutubeClientException`
  → `502`; individual video failures are logged and skipped rather than failing the whole job
- Gemini rate limiting → one retry with backoff inside `GeminiClient`, then `GeminiApiException` → `503`

## Testing

- `ChunkingServiceTest` — pure unit tests, fabricated transcript segments, no network/DB
- `ChatServiceTest` — Mockito-mocked `ChunkRepository`/`GeminiClient`/`ChannelRepository`/`VideoRepository`,
  asserts prompt assembly and citation URL formatting
