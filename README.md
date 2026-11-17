# DriveAtlas

DriveAtlas is a review-first knowledge workspace for organizing and exploring files a user explicitly selects from Google Drive.

## Release 0 — Foundations and safe Drive access

Release 0 includes a React + TypeScript + Vite frontend, Java 21 + Spring Boot REST API, PostgreSQL/Flyway schema, Docker Compose development environment, GitHub Actions CI, OpenAPI contract, health probes, and a security/privacy review.

The current app supports Google sign-in, selecting files/folders through Google Picker, saving account-owned selection metadata, removing a selection, and deleting the DriveAtlas account data on disconnect. It does not read or index file contents, request broad Drive access, store Drive tokens, or modify/delete original Drive files. A selected folder is recorded without enumerating its contents.

### Technology

- Frontend: React, TypeScript, Vite, nginx
- Backend: Java 21, Spring Boot, Spring Security OAuth 2.0/OIDC, JDBC
- Persistence: PostgreSQL 16, Flyway migrations
- Infrastructure: Docker Compose, GitHub Actions

## Run locally

Requirements: Docker Desktop with Compose, a Google Cloud project, and a Google OAuth web client.

1. Copy `.env.example` to `.env` and set `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_API_KEY`, and `GOOGLE_PROJECT_NUMBER`. For local use, leave `SESSION_COOKIE_SECURE=false`; use `true` behind HTTPS in production. Replace the sample PostgreSQL password.
2. In the same Google Cloud project, enable the Google Picker API, create the OAuth web client and API key, and use that project's number for `GOOGLE_PROJECT_NUMBER`. Configure the OAuth client with JavaScript origin `http://localhost:8080` and redirect URI `http://localhost:8080/login/oauth2/code/google`. For the API key's website (HTTP-referrer) restrictions, allow both `http://localhost:8080/*` and `https://docs.google.com/*`; the Picker runs in an iframe hosted on `docs.google.com`. If the key has API restrictions, allow the Google Picker API. This release does not call the Drive API directly.
3. Start the stack:

   ```powershell
   docker compose up --build
   ```

4. Open `http://localhost:8080`, connect Google, and select a small set of test files. Verify their metadata is listed, remove individual entries, then use **Disconnect** to delete all DriveAtlas account data. Check PostgreSQL's `drive_resource` table to confirm scope and deletion behavior. The app does not access Drive file contents.
5. Stop the stack with `Ctrl+C`. `docker compose down` stops containers; `docker compose down -v` also permanently removes the local PostgreSQL volume.

The frontend embeds these Google settings at image build time. After changing them in `.env`, rebuild the web service with `docker compose up --build -d web`.

The `.env` file is ignored by Git. Never commit OAuth secrets or production credentials.

## Development and checks

- Frontend: `cd frontend; npm ci; npm run dev` (with the API available at `localhost:8080`).
- Frontend production build: `cd frontend; npm ci; npm run build`.
- Backend tests: `cd backend; mvn verify`.
- Compose: `docker compose up --build`.

The frontend dev server proxies API, login, and logout routes to the backend. Set `GOOGLE_CLIENT_ID` in the frontend build to enable Google Picker.

## Project layout

```text
backend/       Spring Boot API, security, persistence, Flyway migrations, tests
contracts/     OpenAPI contract
docs/          Threat and privacy review
frontend/      React application, Google Picker integration, nginx
.github/       CI workflow
docker-compose.yml
```

## Release plan

The product principles, roadmap, acceptance criteria, cloud direction, and quality gates are maintained in this README and remain the project requirements for later releases. Release 1's metadata indexing, extraction, tags, and search are not implemented yet.

## Security review

Read [docs/security-and-privacy.md](docs/security-and-privacy.md) before connecting real user accounts. Production file processing is gated on the residual risks and controls described there.

## Product principles

- Process only files and folders the user explicitly selects.
- Require review before any write; generated or merged content must be saved as a new file only after confirmation.
- Preserve provenance for extracted passages, tags, comparisons, and generated claims.
- Clearly identify unsupported formats rather than implying they were parsed.
- Use least-privilege Google authorization, protect credentials, and provide disconnect and deletion controls.
- Keep cloud deployment portable with containers, PostgreSQL, environment configuration, and provider-independent interfaces.

## Intended workflow

1. Connect a Google account and select specific files or folders using a user-facing picker.
2. View selected files in a mirrored folder tree with indexing status and supported-format indicators.
3. Extract supported content and metadata, then suggest editable topic tags with evidence and confidence.
4. Explore files by folder, tag, text search, and topic/document relationships.
5. Compare versions of the same supported text document, or compose a new reviewable document from multiple sources.
6. Preview the result, inspect citations and source links, then save it as a new Drive file only after user confirmation.

## Roadmap

### Release 1 — Drive library and editable topic tags

- Mirror selected folder structure and list selected files with name, MIME type, modified time, and source link.
- Index metadata reliably and show queued, processing, ready, unsupported, and failed states.
- Support manual tags and reviewable AI tag suggestions with confidence and evidence.
- Add folder, tag, and file-type filters.
- Keep file contents out of logs and do not send content to AI unless a user-facing feature requires it.
- Initial content support: metadata for selected file types, extraction initially limited to plain text/Markdown and exportable Google Docs.

### Release 2 — Search, extraction, and grounded retrieval

- Add PDF extraction, including scanned-PDF OCR, and other formats only as separate, tested adapters with documented limits and page-level provenance.
- Evaluate [Docling](https://docling-project.github.io/docling/) or equivalent as an extraction/OCR adapter; do not add a separate runtime or promise its full format list until accuracy, operational cost, privacy, and licensing are evaluated.
- Add full-text search across successfully extracted content.
- Consider pgvector only if semantic retrieval is justified, with source links and page/section references.
- If evaluation justifies embeddings, add reviewable topic/document grouping and semantic-overlap/possible-duplicate suggestions with source evidence.
- Evaluate retrieval relevance, grouping and duplicate-suggestion precision/recall, source attribution, extraction quality, unsupported, and corrupt documents. Calibrate thresholds against this evaluation set; do not treat universal cosine cutoffs as truth.
- Never silently collapse or delete semantic duplicates. Preserve every original and let the user decide whether passages are duplicates, complementary, or contradictory.

### Release 3 — Compare and compose safely

- Compare versions of the same supported text document using deterministic diff first.
- Offer three-way conflict resolution only for the same document with a known common base.
- Provide a review UI for same-document conflicts and multi-source composition: show source passages beside the proposed draft, cite each claim, and let users accept, edit, or keep source contributions before saving.
- Compose drafts from multiple source files with citations/provenance; do not confuse synthesis with version merging or apply Git conflict resolution to unrelated documents.
- Review every change and save approved results as new Drive files only after confirmation; never overwrite, archive, trash, or delete originals as an automatic deduplication step.
- Initial merge support: plain text and Markdown.

### Release 4 — Background indexing and operations

- Add RabbitMQ/Spring AMQP for durable asynchronous indexing only when long-running jobs are introduced.
- Track durable state in PostgreSQL with idempotency, bounded retries, dead-letter handling, and visible errors.
- Add Redis only for measured cache use cases; PostgreSQL remains the source of truth.
- Add metrics, quotas, file-size limits, rate limits, and backpressure.

### Release 5 — Drive labels and collaboration (optional)

- Evaluate the Drive Labels API; app-only tags remain the default.
- Add sharing only after authorization, ownership, and audit requirements are defined.
- Keep browser notifications opt-in and provide in-app status.

## Existing tools and market check

Checked **2026-11-17** against the projects' current documentation and repositories. The claim that no open-source tool covers the entire proposed combination is plausible, but a web/GitHub spot-check cannot prove a universal negative. There are meaningful partial matches, including tools that combine Drive sync with merge features:

| Tool | What its published project materials support | DriveAtlas implication |
|---|---|---|
| [rclone](https://rclone.org/drive/) + [Docling](https://docling-project.github.io/docling/) | Drive file operations and Google Workspace exports; Docling parses many formats and provides OCR options. | Useful references, not a ready-made selected-file ingestion pipeline. rclone's docs say its `drive.file` scope is limited to files it creates, while DriveAtlas must honor user-selected existing items. Keep the Drive Picker/API boundary and evaluate extraction separately. |
| [Obsidian Google Drive Merge Sync](https://github.com/kebl3541/Obsidian-Google-Drive-Merge-Sync) and [MDSync](https://github.com/k1tbyte/mdsync) | Obsidian-vault sync with file history and three-way conflict handling for notes; MDSync also documents a merge editor and Google Drive storage. | These are real competitors for Markdown sync/conflict resolution. They do not establish the proposed OCR, semantic topic grouping, cross-document deduplication, and provenance-grounded composition workflow. |
| [Smart Connections](https://github.com/brianpetro/obsidian-smart-connections) | Finds related Obsidian notes/excerpts using embeddings and semantic search. | Prior art for related-content discovery, not evidence that fixed similarity thresholds safely deduplicate documents. |
| [Paperless-ngx](https://github.com/paperless-ngx/paperless-ngx) and [Papermerge OSS](https://github.com/papermerge/papermerge-core) | Document archiving/management; Papermerge documents OCR, full-text search, tags, folders, and document versioning. | Strong archival/OCR references, but their documented core does not describe Drive-scoped semantic composition with a human-reviewed merge workflow. |
| [Khoj](https://docs.khoj.dev/) | Self-hostable personal AI for document search/chat, including PDFs and notes. | Prior art for document retrieval and Q&A, not the review-first version-merge and export workflow. |
| [AnythingLLM](https://docs.anythingllm.com/) | Self-hostable LLM workspaces, document retrieval, and agent features. | A useful RAG/workspace comparison; its published feature overview is not a source-grounded document merge workflow. |
| [Meld](https://github.com/GNOME/meld), [KDiff3](https://github.com/KDE/kdiff3), and [VS Code's merge editor](https://code.visualstudio.com/docs/sourcecontrol/merge-conflicts) | Visual text/file comparison and conflict-resolution tools. | Useful interaction references for Release 3; they are not document ingestion, OCR, or semantic knowledge systems. |

“DocFinder” is too ambiguous a name to verify as one specific project, and the “killer/recruiter-ready” characterization is opinion rather than a technical finding. This comparison is a product-planning snapshot, not an endorsement or a claim that the listed projects lack every feature not mentioned here.

## Explicit non-goals

- Parsing or merging arbitrary binary formats.
- Silent edits to, replacement of, or deletion of original Drive files.
- Git-style three-way conflict resolution for unrelated files.
- Recursive Drive-wide scans or ingestion of arbitrary local folders; local-folder support would need a separate scope, authorization, and release decision.
- Drive-visible labels in initial releases.
- Unrestricted Drive-wide access or indexing files the user did not select.

## Quality and acceptance gates

- Test OAuth boundaries, Drive adapter authorization, extraction, permissions, and persistence.
- Add frontend/backend contract tests and deterministic mocked AI providers; ordinary CI must not depend on a live AI provider.
- Test large, unsupported, malformed, corrupt, and revoked-access files, retries, and duplicate jobs as those features are introduced.
- Verify users cannot read another account's metadata, extracted content, tags, or generated results.
- Every release must retain original Drive files unless a user explicitly confirms a supported write action.

## Cloud deployment direction

Deploy standard containers with external PostgreSQL and optional Redis/RabbitMQ. Keep application logic independent of provider-specific managed queues. Potential targets include Google Cloud Run, AWS App Runner/ECS, or Azure Container Apps. Use environment-based configuration, migrations, health/readiness endpoints, and external secret management. Local Docker Compose remains the complete development option. Compare current free-tier limits, persistence, queue support, quotas, and API/AI costs before choosing a host.
