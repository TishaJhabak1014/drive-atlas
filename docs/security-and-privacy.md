# DriveAtlas security and privacy review

## Scope and release gate

The current implementation provides Google sign-in, explicit Google Picker selection, user-triggered folder metadata scans, account-scoped resource metadata and manual tags, and browser-side extraction of supported text from files in selected-resource scopes. Users may retain extracted text in their account or keep it in browser memory only. Optional Gemini tag suggestions and multi-source consolidation send text to Google Gemini only after in-product disclosure and confirmation. Resolve the residual risks below before production use.

## OAuth and selected-file access

- Authenticate the application with Google's OpenID Connect authorization-code login. The backend requests only `openid`, `email`, and `profile`.
- The browser obtains a short-lived access token from Google Identity Services. Picker uses `drive.file`; an explicit folder scan requests `drive.metadata.readonly`; text extraction requests `drive.readonly`. Google grants both read-only scopes broadly across the user's Drive and classifies them as restricted. DriveAtlas limits scans to the selected folder subtree and content access to files that have membership in a directly selected file or selected-folder scope. The backend checks that scope membership against the authenticated account before content reads, writes, deletes, or tag suggestions.
- The Picker authorization hints the signed-in email; users should confirm that Picker is showing the same Google account.
- The token is held only in JavaScript memory and sent directly to Google Picker, the Google user-info endpoint, and the Drive API. It is not sent to DriveAtlas APIs, saved in browser storage, logged, or persisted in PostgreSQL. The app does not explicitly revoke it when a Picker or scan closes; it remains a short-lived access token, while the user's consent remains until revoked in Google Account settings.
- A selected folder is only enumerated after the user presses **Scan folder**. The browser calls the Drive API with the short-lived token and requests metadata fields only; results are capped at 5,000 descendants and the cap is reported rather than silently truncating.
- Metadata traversal uses at most three concurrent folder workers. It remains user-initiated, metadata-only, and bounded by the descendant limit.
- Text extraction is browser-side and limited to plain text, Markdown, and Google Docs exported as plain text, with a 500 KB response limit. Supported files in a scanned folder scope can be extracted without selecting each descendant individually. PDF and image OCR are not yet supported.
- Only metadata returned from the authorized Drive API call is submitted for the selected root. The API checks that the root is an explicitly selected folder and that the submitted parent hierarchy is rooted there. The browser remains an untrusted client, so direct API callers can still forge their own account's metadata; no other account's records can be read or modified.

## Identity, ownership, retention, and deletion

- Google OIDC `sub` is the stable account key; email is profile metadata, not an ownership key.
- Every resource query, update, and delete is constrained by the account ID resolved from the authenticated OIDC subject. Unauthenticated API requests receive `401`.
- Database foreign keys cascade account deletion to selected resource metadata, manual tags, and optionally retained extracted text. Disconnect requires confirmation, deletes the account and its app data, then invalidates the application session. Users can also explicitly forget saved text. It never changes original Drive files.
- The OIDC session is held in an HTTP-only, SameSite=Lax cookie. Set `SESSION_COOKIE_SECURE=true` behind HTTPS in production. CSRF protection applies to state-changing requests.
- No Drive access or refresh token is stored server-side. The database contains account identity, user-selected and discovered resource metadata, user-entered tags, and only the extracted text the user chose to retain.

## Content processing and AI features

- Extracted text is retained only when the user leaves **Save extracted text for later** checked (the default). Unchecked extraction remains in browser memory for that session; if an older saved copy exists, extracting with retention unchecked deletes that saved copy. Text is account-scoped in PostgreSQL and can be forgotten per file or removed by disconnect.
- Multi-source consolidation offers concise SUMMARIZE and detail-preserving FULL modes. Both require explicit confirmation and submit 2–10 source IDs and texts (up to 60,000 combined characters) to an authenticated DriveAtlas endpoint. The service verifies each source is in the user's selected Drive scope and supports text composition before forwarding content to Gemini. Gemini may receive one additional correction request if its response is malformed or fails evidence validation. FULL mode aims to preserve unique details and remove only semantic repetition; it flags contradictions found among selected sources and does not use external fact-checking to identify objectively false claims. Neither mode guarantees perfect preservation, so users must review the draft, source list, and citations. The Gemini API key stays on the server. Gemini request/response logging is disabled, source text and generated drafts are not stored by the application, and the returned Markdown remains in browser memory until the user downloads it. Original Drive files are unchanged.
- Tag-suggestion requests also require explicit confirmation and send at most 30,000 characters to Gemini. Gemini request/response logging is disabled. Free-tier terms can change; [Google's Gemini API pricing and data-use terms](https://ai.google.dev/gemini-api/docs/pricing) state that free-tier content may be used to improve Google products, so users should not submit sensitive or confidential content.
- Tag suggestions and consolidation results are transient and are not automatically applied or saved. The service validates tag bounds and confidence for suggestions, and requires cited evidence for both features to occur exactly in submitted source text. Users must accept a tag suggestion before it becomes an ordinary manual tag; consolidation output must be reviewed and downloaded by the user.
- Extraction and AI requests are never made automatically during metadata-only folder scans. A user must explicitly trigger extraction for an eligible file and explicitly confirm before its text is sent to Gemini. Content and AI provider failures should be surfaced to the user rather than treated as successful processing.

## Threats and mitigations

| Threat | Current mitigation | Remaining work |
|---|---|---|
| Broad Drive permissions | Request `drive.metadata.readonly` only for explicit scans and `drive.readonly` only for text extraction; the backend permits content operations only for resources in selected scopes | Complete Google's restricted-scope verification/security assessment for both scopes before production; review XSS and token-exposure risks |
| Cross-account metadata access | Ownership-scoped SQL and OIDC subject mapping | Add isolation tests for every future data endpoint |
| CSRF against session endpoints | Cookie-backed CSRF token and required request header | Re-test with production proxy/domain and browser settings |
| Token disclosure | Short-lived access token only in memory and used directly with Google; no app-side logging | Add automated browser checks and a strict third-party script/CSP policy before production |
| Malicious or stale client metadata, including a different Google account in Picker | Length validation, account hint, and parameterized SQL | Server-side Google API verification of account and resource is mandatory before processing |
| Accidental source modification | No Drive write APIs or scopes are requested | Keep export/write capabilities gated behind a separate, reviewed feature |
| Sensitive data in logs or sent to external AI | Gemini is opt-in behind confirmation; request/response logging is disabled; source text is not logged or retained by the application | Verify production logging/redaction and communicate Google's current free-tier data-use terms before enabling Gemini |
| Unintended data retention | Explicit account deletion with database cascade | Backups and operational copies need a documented expiry/deletion policy |

## Before production data

1. Configure HTTPS, secure cookies, a strict Content Security Policy, secret management, database encryption/backups, and least-privilege database credentials.
2. Verify Google OAuth consent-screen publication and restricted `drive.metadata.readonly` and `drive.readonly` requirements, Picker API restrictions, allowed origins, redirect URIs, and token revocation behavior.
3. Add automated authorization, CSRF, account-deletion, extracted-text retention, AI-provider, and resource-isolation tests; run a threat review against the deployed topology.
4. Define backup deletion, account recovery, audit, privacy-notice, and retention policies. Keep logs free of selected document contents and tokens.
5. Add a server-side Drive adapter only after it independently verifies the selected root, constrains queries to that subtree, and enforces size/type limits.
