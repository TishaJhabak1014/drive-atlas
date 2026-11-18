# DriveAtlas

DriveAtlas is a review-first knowledge workspace for organizing and exploring files a user explicitly selects from Google Drive.

## Release 0 — Foundations and safe Drive access

Release 0 includes a React + TypeScript + Vite frontend, Java 21 + Spring Boot REST API, PostgreSQL/Flyway schema, Docker Compose development environment, GitHub Actions CI, OpenAPI contract, health probes, and a security/privacy review.

The current app supports Google sign-in, selecting files/folders through Google Picker, saving account-owned metadata, explicitly scanning selected folders, and applying manual tags. Users can extract plain text, Markdown, and Google Docs text from supported files in selected resources, including scanned folder descendants; saving the extracted text is optional and checked by default. Content extraction requests Google's restricted, Drive-wide read-only OAuth scope, while DriveAtlas limits extraction to files in the user's selected-resource scopes. Users can request reviewable Gemini tag suggestions, with confidence and exact supporting evidence, and must accept suggestions before they become tags. Merge-summarize and merge-in-full send 2–10 explicitly selected text sources (up to 60,000 combined characters) to Gemini only after confirmation, then return a citation-grounded draft with merge notes for review and Markdown download. Merge-in-full aims to retain and refine unique details while removing semantic repetition; contradictions between selected sources are flagged rather than externally fact-checked or silently discarded. The selected source list clears after a successful merge. Generated drafts are not stored by DriveAtlas or written to Drive. DriveAtlas does not store Drive tokens or modify/delete original Drive files.

### Technology

- Frontend: React, TypeScript, Vite, nginx
- Backend: Java 21, Spring Boot, Spring Security OAuth 2.0/OIDC, JDBC, LangChain4j Gemini integration
- Persistence: PostgreSQL 16, Flyway migrations
- Infrastructure: Docker Compose, GitHub Actions

## Run locally

Requirements: Docker Desktop with Compose, a Google Cloud project, and a Google OAuth web client.

1. Copy `.env.example` to `.env` and set `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_API_KEY`, and `GOOGLE_PROJECT_NUMBER`. `GEMINI_API_KEY` is optional and enables AI tag suggestions and document merges; `GEMINI_MODEL` defaults to `gemini-3.5-flash-lite`. For local use, leave `SESSION_COOKIE_SECURE=false`; use `true` behind HTTPS in production. Replace the sample PostgreSQL password.
2. In the same Google Cloud project, enable the Google Picker API and Google Drive API, create the OAuth web client and API key, and use that project's number for `GOOGLE_PROJECT_NUMBER`. In Google Auth Platform **Data Access**, configure `openid`, `email`, `profile`, `https://www.googleapis.com/auth/drive.file`, `https://www.googleapis.com/auth/drive.metadata.readonly`, and `https://www.googleapis.com/auth/drive.readonly`. Folder scans request `drive.metadata.readonly`; text extraction requests `drive.readonly`. Google grants these scopes broadly across Drive, but DriveAtlas only scans selected folder subtrees and extracts files represented in selected-resource scopes. Both Drive scopes are restricted, so complete Google's verification and security-assessment requirements before production use. Configure the OAuth client with JavaScript origin `http://localhost:8080` and redirect URI `http://localhost:8080/login/oauth2/code/google`. For the API key's website (HTTP-referrer) restrictions, allow both `http://localhost:8080/*` and `https://docs.google.com/*`; the Picker runs in an iframe hosted on `docs.google.com`. If the key has API restrictions, allow both the Google Picker API and Google Drive API.
3. Start the stack:

   ```powershell
   docker compose up --build
   ```

4. Open `http://localhost:8080`, connect Google, and select a small set of test files and a test folder. On the first **Scan folder** action, review and approve Google's metadata-only scope consent. Folder scans are limited to 5,000 descendants and retrieve metadata only. For a supported `.txt`, `.md`, or Google Doc selected directly or found in the scanned folder, try **Extract text** and review the retention checkbox. Extraction triggers a separate Google consent for broad read-only Drive access; DriveAtlas itself limits content reads to files in selected-resource scopes. Saved content can be viewed and explicitly forgotten. Extract at least two supported files, select **Include in Gemini consolidation**, then choose **Merge-summarize** for a concise synthesis or **Merge-in-full** to retain and refine details while removing semantic repetition. The confirmation lists the exact selected files before sending. Review merge notes and exact evidence citations before downloading. Full mode flags contradictions among selected files but does not perform external fact-checking; it does not yet provide per-change accept/reject controls. Source selections clear after a successful merge. If Gemini is configured, you can also try **Suggest tags with Gemini**. Google states that content submitted through the [free Gemini API tier](https://ai.google.dev/gemini-api/docs/pricing) may be used to improve Google products; do not send confidential or sensitive text. Verify suggestions before accepting them. Then use **Disconnect** to delete DriveAtlas account data. Original Drive files are never modified or deleted.
5. Stop the stack with `Ctrl+C`. `docker compose down` stops containers; `docker compose down -v` also permanently removes the local PostgreSQL volume.

The frontend embeds the Google Picker settings at image build time. `GEMINI_API_KEY` stays server-side. After changing frontend settings in `.env`, rebuild the web service with `docker compose up --build -d web`; rebuild the API if changing its environment.

The `.env` file is ignored by Git. Never commit OAuth secrets or production credentials.

## Staging deployment

The current staging deployment guide targets Google Cloud Run with the existing managed Neon PostgreSQL project. It builds the React frontend into the Spring Boot image for same-origin routing, uses Secret Manager for runtime credentials, and documents OAuth branding, the privacy notice, data retention, rollback, and smoke checks. See [docs/cloud-run-staging.md](docs/cloud-run-staging.md). Do not deploy the local Compose PostgreSQL volume as a hosted database.

## Development and checks

- Frontend: `cd frontend; npm ci; npm run dev` (with the API available at `localhost:8080`).
- Frontend production build: `cd frontend; npm ci; npm run build`.
- Frontend tests: `cd frontend; npm test` (Node.js 22+).
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

The product principles, roadmap, acceptance criteria, cloud direction, and quality gates are maintained in this README and remain the project requirements for later releases. Release 2 is partially implemented: text extraction and grounded tag suggestions are available, while full-text search and PDF/image OCR are deferred. Release 3 has started with AI-assisted merge-summarize and merge-in-full modes; same-document revision comparison and interactive conflict resolution remain planned.

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
3. Extract supported content and metadata, then review editable topic-tag suggestions with evidence and confidence.
4. Explore files by folder, tag, text search, and topic/document relationships.
5. Compare versions of the same supported text document, or compose a new reviewable document from multiple sources.
6. Preview the result, inspect citations and source links, then save it as a new Drive file only after user confirmation.

## Roadmap

### Release 1 — Drive library and editable topic tags

- Implemented: persistent, account-scoped manual tags for explicitly selected resources.
- Implemented: folder, tag, and MIME-type filters; explicit selected-folder scans upsert descendant metadata and replace each folder's prior scan snapshot.
- Implemented: render scanned metadata as a collapsible parent-child folder tree while retaining resource filters.
- Implemented: persist and display the last metadata refresh time for each resource; existing resources show no refresh time until they are next selected or scanned.
- Implemented: metadata-only selected-folder scans with a bounded three-worker folder traversal and visible progress.
- Implemented: browser-side text extraction for plain text, Markdown, and exportable Google Docs in selected-resource scopes (including scanned descendants), with a 500 KB limit.
- Implemented: optional account-scoped extracted-text retention, view, refresh, explicit deletion, and deletion on disconnect; retention is selected per extraction and defaults on.
- Implemented: optional Gemini tag suggestions through LangChain4j; users see the free-tier disclosure before sending up to 30,000 characters, and suggestions require manual acceptance.
- Keep extracted text and AI prompts/responses out of logs; do not send content to AI without explicit confirmation.
- Remaining: surface unsupported/failed processing states consistently and validate content/account isolation against PostgreSQL in an integration test.

### Release 2 — Search, extraction, and grounded retrieval

- Add browser-local PDF extraction, including scanned-PDF OCR, and image OCR as separate, tested adapters with documented limits and page-level provenance.
- Evaluate PDF.js plus Tesseract.js or equivalent browser-local libraries; retain document data in the browser and do not send it to a remote OCR service. Validate accuracy, memory/runtime limits, accessibility, and licensing before adoption.
- Add full-text search across successfully extracted content.
- Consider pgvector only if semantic retrieval is justified, with source links and page/section references.
- If evaluation justifies embeddings, add reviewable topic/document grouping and semantic-overlap/possible-duplicate suggestions with source evidence.
- Evaluate retrieval relevance, grouping and duplicate-suggestion precision/recall, source attribution, extraction quality, unsupported, and corrupt documents. Calibrate thresholds against this evaluation set; do not treat universal cosine cutoffs as truth.
- Never silently collapse or delete semantic duplicates. Preserve every original and let the user decide whether passages are duplicates, complementary, or contradictory.

### Release 3 — Compare and compose safely

- Implemented: Gemini merge-summarize and detail-preserving merge-in-full for 2–10 extracted supported files and up to 60,000 combined characters, with exact source-evidence validation, source links, merge notes, review/edit, and user-initiated Markdown download. Full mode flags source contradictions and does not claim external fact-checking. The result is not stored by DriveAtlas, and originals are never changed.
- Compare versions of the same supported text document using deterministic diff first.
- Offer three-way conflict resolution only for the same document with a known common base.
- Add source-section reordering and a color-coded per-change review UI with accept/reject and source-contribution controls; the current composer does not provide these controls.
- Provide an interactive review UI for multi-source consolidation: show source passages beside the proposed draft, color-code contributions, and let users accept, reject, edit, or keep source content before saving.
- Evaluate source coverage, evidence-grounding, usefulness, and contradiction handling; citations improve traceability but do not prove that the consolidation preserved every meaningful fact. Do not confuse synthesis with version merging or apply Git conflict resolution to unrelated documents.
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

- Test OAuth boundaries, Drive adapter authorization, extraction, permissions, content retention, and persistence.
- Add frontend/backend contract tests and deterministic mocked AI providers; ordinary CI must not depend on a live AI provider.
- Test large, unsupported, malformed, corrupt, and revoked-access files, retries, and duplicate jobs as those features are introduced.
- Verify users cannot read another account's metadata, extracted content, tags, or generated results.
- Every release must retain original Drive files unless a user explicitly confirms a supported write action.

## Cloud deployment direction

Deploy standard containers with external PostgreSQL and optional Redis/RabbitMQ. Keep application logic independent of provider-specific managed queues. Potential targets include Google Cloud Run, AWS App Runner/ECS, or Azure Container Apps. Use environment-based configuration, migrations, health/readiness endpoints, and external secret management. Local Docker Compose remains the complete development option. Compare current free-tier limits, persistence, queue support, quotas, and API/AI costs before choosing a host.
