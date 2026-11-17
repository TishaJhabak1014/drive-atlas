# DriveAtlas security and privacy review

## Scope and release gate

The current implementation provides Google sign-in, explicit Google Picker selection, storage of selected-resource metadata, and user-entered manual tags. Tags are account-scoped app data; they are not inferred from or compared with Drive contents. DriveAtlas does **not** read, download, extract, classify, or log file contents. Do not enable file processing or onboard real user data beyond this workflow until the residual risks below are resolved and reviewed.

## OAuth and selected-file access

- Authenticate the application with Google's OpenID Connect authorization-code login. The backend requests only `openid`, `email`, and `profile`.
- The browser obtains a short-lived access token from Google Identity Services only after the user opens the Picker. The Drive scope is `https://www.googleapis.com/auth/drive.file`; the app does not request broad `drive` or `drive.readonly` access.
- The Picker authorization hints the signed-in email; users should confirm that Picker is showing the same Google account.
- The token is held only in JavaScript memory and passed to Google Picker. It is not sent to DriveAtlas APIs, saved in browser storage, logged, or persisted in PostgreSQL. The app does not explicitly revoke it when Picker closes; it remains a short-lived access token, while the user's consent remains until revoked in Google Account settings.
- Only the Picker items the user selects are sent as metadata to the API. Selecting a folder records that folder only; the app does not enumerate its descendants.
- The API does not independently verify the Picker assertion or fetch Drive metadata in this release. The browser is not a trusted source; the metadata is display-only, and no content processing is available. Before any server-side Drive reads are added, validate each resource against the signed-in user's `drive.file` authorization at the Google API boundary.

## Identity, ownership, retention, and deletion

- Google OIDC `sub` is the stable account key; email is profile metadata, not an ownership key.
- Every resource query, update, and delete is constrained by the account ID resolved from the authenticated OIDC subject. Unauthenticated API requests receive `401`.
- Database foreign keys cascade account deletion to selected resource metadata and manual tags. Disconnect requires confirmation, deletes the account and its app data, then invalidates the application session. It never changes original Drive files.
- The OIDC session is held in an HTTP-only, SameSite=Lax cookie. Set `SESSION_COOKIE_SECURE=true` behind HTTPS in production. CSRF protection applies to state-changing requests.
- No Drive access or refresh token is stored server-side. The database contains only account identity and user-selected provider metadata.

## Threats and mitigations

| Threat | Current mitigation | Remaining work |
|---|---|---|
| Over-broad Drive access | `drive.file` and user-mediated Picker only | Verify exact consent and folder behavior with a production Google project |
| Cross-account metadata access | Ownership-scoped SQL and OIDC subject mapping | Add isolation tests for every future data endpoint |
| CSRF against session endpoints | Cookie-backed CSRF token and required request header | Re-test with production proxy/domain and browser settings |
| Token disclosure | Access token only in memory and only used for Picker; no app-side logging | Add automated browser checks and review third-party script/CSP policy |
| Malicious or stale client metadata, including a different Google account in Picker | Length validation, account hint, and parameterized SQL | Server-side Google API verification of account and resource is mandatory before processing |
| Accidental source modification | No Drive write APIs or scopes are requested | Keep export/write capabilities gated behind a separate, reviewed feature |
| Sensitive data in logs | No file contents or access tokens enter the API | Establish log redaction, retention, and operational access controls before production |
| Unintended data retention | Explicit account deletion with database cascade | Backups and operational copies need a documented expiry/deletion policy |

## Before production data

1. Configure HTTPS, secure cookies, a strict Content Security Policy, secret management, database encryption/backups, and least-privilege database credentials.
2. Verify Google OAuth consent-screen publication, app verification requirements, Picker API restrictions, allowed origins, redirect URIs, and token revocation behavior.
3. Add automated authorization, CSRF, account-deletion, and resource-isolation tests; run a threat review against the deployed topology.
4. Define backup deletion, account recovery, audit, privacy-notice, and retention policies. Keep logs free of selected document contents and tokens.
5. Add a server-side Drive adapter only after it verifies each requested resource with the authenticated user's narrow scope and enforces size/type limits.
