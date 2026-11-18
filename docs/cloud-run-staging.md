# Cloud Run staging deployment

This guide deploys the current app as one Cloud Run service and uses the existing Neon PostgreSQL project. Cloud Run serves the built React files and Spring API from the same origin, so the separate Nginx `api` proxy used by local Compose is not needed. Spring Security protects application APIs behind Google sign-in.

The staging target is `asia-southeast1` to keep Cloud Run close to the existing Neon project shown in the Neon console (AWS Asia Pacific, Singapore). This is a low-traffic portfolio staging setup, not a production availability or backup guarantee. Keep the Cloud Run minimum instance count at zero and monitor Google Cloud billing. Free allowances are quotas, not a spending cap; traffic, builds, egress, Gemini, and plan changes can create charges.

## 1. Public privacy notice and support

The app serves [privacy.html](../frontend/public/privacy.html) before sign-in. The landing-page footer links to it and to `tishajhabak22@gmail.com`. The notice explains current data collection, Google Drive and Gemini use, Neon storage, account deletion, and recovery-history limits. Review it against the final Neon project settings before inviting people. Roadmap features are not described as active.

## 2. Google Cloud and Neon setup

Use the existing project `driveatlas-515014` for this staging service. The Google Cloud CLI is required for the commands below; it is not installed in the current workspace. They can be run in Cloud Shell after cloning the repository, or locally after installing `gcloud` and authenticating.

Enable Cloud Run, Cloud Build, Artifact Registry, and Secret Manager APIs. Create an Artifact Registry Docker repository named `driveatlas` in `asia-southeast1`. Grant the Cloud Build service account permission to write to that repository.

```powershell
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com --project driveatlas-515014
gcloud artifacts repositories create driveatlas --repository-format docker --location asia-southeast1 --project driveatlas-515014
gcloud iam service-accounts create driveatlas-runtime --project driveatlas-515014
```

Create a runtime service account named `driveatlas-runtime`. Store these values in Secret Manager, never in Git or a Docker build argument:

- `DATABASE_URL`: JDBC URL for the Neon database, e.g. change the Neon `postgresql://` scheme to `jdbc:postgresql://` and retain `sslmode=require`.
- `DATABASE_USERNAME` and `DATABASE_PASSWORD`: the Neon database role and password.
- `GOOGLE_CLIENT_SECRET`: the existing OAuth web client secret.
- `GEMINI_API_KEY`: the server-side Gemini key. This is optional; without it, Gemini actions are disabled.

Grant the runtime service account `Secret Manager Secret Accessor` on only those secrets. Use Neon's pooled connection URL for the Singapore production branch and verify it connects with TLS. Do not include the database username or password in `DATABASE_URL` when also supplying the separate username/password secrets.

The Google OAuth client ID, Picker API key, and Google project number are public browser configuration and are baked into the frontend image. Use the existing API key with HTTP referrer restrictions for the eventual Cloud Run service URL and `https://docs.google.com/*`, restricted to the Google Picker API and Drive API. The Cloud Run URL is only known after first deployment, so initially deploy, copy the generated `run.app` URL, and then finish these Google settings.

## 3. Build a versioned image

Deploy from a clean, committed checkout that passed the GitHub Actions workflow. Use the commit SHA as an immutable image tag so the running image can be tied to the reviewed source. In PowerShell, set the public browser values from the local ignored `.env` without printing the file:

```powershell
$driveAtlasSettings = @{}
Get-Content .env | Where-Object { $_ -match '^[A-Z0-9_]+=' } | ForEach-Object {
  $parts = $_ -split '=', 2
  $driveAtlasSettings[$parts[0]] = $parts[1]
}
$driveAtlasVersion = git rev-parse --short=12 HEAD
$driveAtlasImage = "asia-southeast1-docker.pkg.dev/driveatlas-515014/driveatlas/driveatlas:staging-$driveAtlasVersion"
gcloud config set project driveatlas-515014
gcloud builds submit --region asia-southeast1 --config cloudbuild.yaml `
  --substitutions="_IMAGE_TAG=staging-$driveAtlasVersion,_VITE_GOOGLE_CLIENT_ID=$($driveAtlasSettings.GOOGLE_CLIENT_ID),_VITE_GOOGLE_API_KEY=$($driveAtlasSettings.GOOGLE_API_KEY),_VITE_GOOGLE_APP_ID=$($driveAtlasSettings.GOOGLE_PROJECT_NUMBER)" .
```

The `.gcloudignore` excludes `.env`, Git internals, local dependencies, and build output from uploads. Check the build result before deploying the image.

## 4. Deploy the staging service

Set `GOOGLE_CLIENT_ID` to the same OAuth client ID used in the build. Replace secret names if you chose different names in Secret Manager.

```powershell
gcloud run deploy driveatlas-staging `
  --image $driveAtlasImage `
  --region asia-southeast1 `
  --project driveatlas-515014 `
  --allow-unauthenticated `
  --service-account driveatlas-runtime@driveatlas-515014.iam.gserviceaccount.com `
  --port 8080 --cpu 1 --memory 1Gi --min 0 --max 2 --concurrency 20 `
  --set-env-vars "GOOGLE_CLIENT_ID=$($driveAtlasSettings.GOOGLE_CLIENT_ID),SESSION_COOKIE_SECURE=true,FORWARDED_HEADERS_STRATEGY=framework,GEMINI_MODEL=$($driveAtlasSettings.GEMINI_MODEL)" `
  --set-secrets "DATABASE_URL=DATABASE_URL:latest,DATABASE_USERNAME=DATABASE_USERNAME:latest,DATABASE_PASSWORD=DATABASE_PASSWORD:latest,GOOGLE_CLIENT_SECRET=GOOGLE_CLIENT_SECRET:latest,GEMINI_API_KEY=GEMINI_API_KEY:latest"
```

Cloud Run permits public access to the site so recruiters can reach it; Spring Security still requires a Google session for application APIs. The `/actuator/health` endpoint exposes health status only. The service runs Flyway migrations at startup, so keep the max instance count small and deploy one revision at a time.

## 5. Finish Google OAuth and branding

After the service URL appears, configure the OAuth web client:

- Authorized JavaScript origin: the exact `https://...run.app` origin.
- Authorized redirect URI: `https://...run.app/login/oauth2/code/google`.
- OAuth branding: DriveAtlas name, support email `tishajhabak22@gmail.com`, app homepage at the service URL, and privacy policy at `/privacy.html`.
- OAuth audience: keep External / Testing for staging and add each recruiter under Test users before inviting them. Testing grants expire after seven days. Current Drive scan and extraction features request restricted `drive.metadata.readonly` and `drive.readonly` scopes. A staging project is exempt from verification while testing; production availability to arbitrary Google accounts requires Google’s applicable verification, and server storage/transmission of restricted data requires an approved security assessment.
- Drive and Picker APIs: ensure both are enabled and the API key is restricted as described above.

Google may require a domain you control for published production branding and OAuth verification. The generated `run.app` URL is suitable for the initial staging URL; if the Google Auth Platform rejects it for domain verification, use a custom domain before publishing the OAuth branding.

## 6. Smoke test and data lifecycle

Use only non-sensitive test files. Verify sign-in, selected-file and folder scans, supported text extraction, retention unchecked/checked, forget saved text, Gemini tag suggestions, both consolidation modes, citations and contradiction notes, Markdown download, and Disconnect. Confirm original Drive files are unchanged and inspect Cloud Run logs for document text, prompts, responses, and tokens.

Set a reminder to review Neon Free usage and recovery history monthly. The current Free plan has a limited point-in-time recovery window (up to six hours or the plan’s data-change limit, whichever is reached first). Disconnect deletes live account rows through database cascades; those rows can remain in Neon recovery history until it expires. The staging policy is to retain no application account rows after Disconnect and no separate application backup exports. This is suitable only for a small demo dataset; use a paid managed database/backup plan before relying on the service for important user data.

To roll back, deploy the previously tested image tag again. Keep the corresponding Git tag on the exact commit. Database migrations may be forward-only; rollback the application image only when it remains compatible with the current schema.
