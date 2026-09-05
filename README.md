# Ask My Channel

A RAG (Retrieval-Augmented Generation) chatbot over a YouTube creator's own video transcripts.
Ask it a question and it answers grounded in what the channel's videos actually said, citing
the specific video and timestamp — not a generic LLM answer.

Built to be **cloned by any creator**: point it at your own channel and your own free Gemini
API key, and you have your own chatbot. No YouTube Data API key, no billing, no hosting
required beyond your own machine.

## Architecture

Java 21 / Spring Boot 3, layered `controller → service → repository/client`. See
[`docs/architecture.md`](docs/architecture.md) for the full HLD/LLD.

- **Vector search**: PostgreSQL + [pgvector](https://github.com/pgvector/pgvector) (HNSW index, cosine distance)
- **Embeddings + answers**: Google Gemini free tier (`gemini-embedding-001`, `gemini-flash-latest`)
- **Video listing + transcripts**: [`yt-dlp`](https://github.com/yt-dlp/yt-dlp) (no Google Cloud project needed)
- **Multi-tenant by design**: every row is scoped by `channel_id`, so one instance can serve multiple creators

## Prerequisites

- Java 21, Maven
- Docker + Docker Compose
- [`yt-dlp`](https://github.com/yt-dlp/yt-dlp) installed and on your `PATH` (`pip install -U yt-dlp`)
- A free Gemini API key from [aistudio.google.com/apikey](https://aistudio.google.com/apikey) —
  the default project it creates is fine, no billing needed

## Run it

```bash
docker compose up -d                  # starts Postgres with pgvector

export GEMINI_API_KEY=your-key-here
mvn spring-boot:run
```

Open [http://localhost:8080](http://localhost:8080), enter your channel handle
(e.g. `@yourhandle`), click **Ingest channel**, wait for it to finish, then ask it a question.

### API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/channels/{handle}/ingest` | Start ingesting a channel (returns a `jobId` immediately) |
| `GET` | `/api/channels/{handle}/status?jobId=` | Poll ingestion progress |
| `GET` | `/api/channels` | List ingested channels |
| `POST` | `/api/channels/{handle}/chat` | Ask a question — `{"question": "..."}` |

## Testing

```bash
mvn test
```

`ChunkingServiceTest` and `ChatServiceTest` are pure unit tests (no network, no database).

## How it works

1. **Ingestion**: `yt-dlp` lists the channel's uploads. For each video, its auto-generated
   caption track is fetched directly (YouTube's `json3` format — clean, timed, no VTT
   roll-up/dedup mess) and grouped into ~45-second chunks. Each chunk is embedded with Gemini
   and stored in Postgres alongside its `start_seconds`.
2. **Chat**: your question is embedded, pgvector finds the nearest chunks *for that channel
   only*, and Gemini answers using only those excerpts as context. Each citation links to
   `youtu.be/{videoId}?t={seconds}s` — the exact moment.

## Known limitations / future work

- Self-hosted, single/small-group use — not a hosted multi-tenant SaaS with accounts/billing
- Video publish dates aren't captured (channel listing uses `yt-dlp`'s fast flat-playlist mode,
  which doesn't include per-video timestamps)
- Planned stretch goals: a React frontend, an agentic "study path" planner across videos, and
  exposing the chat query as an MCP tool
