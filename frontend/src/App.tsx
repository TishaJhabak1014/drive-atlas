import { useCallback, useEffect, useState } from "react";
import { openDrivePicker, prepareDrivePicker, type SelectedDriveFile } from "./drivePicker";

interface Session {
  subject: string;
  email: string;
  name: string;
}

interface SelectedResource {
  providerId: string;
  name: string;
  mimeType: string;
  resourceType: "FILE" | "FOLDER";
  webViewLink?: string;
  selectedAt: string;
  indexingStatus: string;
  tags: ResourceTag[];
}

interface ResourceTag {
  id: string;
  name: string;
}

const api = async <T,>(path: string, init?: RequestInit): Promise<T> => {
  const headers = new Headers(init?.headers);
  if (init?.body) headers.set("Content-Type", "application/json");
  if (init?.method && init.method !== "GET") {
    const csrf = document.cookie.split("; ").find((cookie) => cookie.startsWith("XSRF-TOKEN="))?.split("=")[1];
    if (csrf) headers.set("X-XSRF-TOKEN", decodeURIComponent(csrf));
  }
  const response = await fetch(path, { ...init, headers, credentials: "same-origin" });
  if (!response.ok) {
    const detail = await response.json().catch(() => undefined) as { detail?: string; title?: string } | undefined;
    throw new Error(detail?.detail ?? detail?.title ?? `Request failed (${response.status}).`);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
};

function safeDriveLink(value?: string): string | undefined {
  return value && /^https:\/\/(drive|docs)\.google\.com\//i.test(value) ? value : undefined;
}

function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [resources, setResources] = useState<SelectedResource[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [busyLabel, setBusyLabel] = useState("Working…");
  const [pickerReady, setPickerReady] = useState(false);
  const [error, setError] = useState("");
  const [tagInputs, setTagInputs] = useState<Record<string, string>>({});

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const current = await api<Session>("/api/v1/session");
      setSession(current);
      setResources(await api<SelectedResource[]>("/api/v1/resources"));
    } catch (reason) {
      if (reason instanceof Error && !reason.message.includes("(401)")) setError(reason.message);
      setSession(null);
      setResources([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);
  useEffect(() => {
    if (!session) return;
    void prepareDrivePicker()
      .then(() => setPickerReady(true))
      .catch(() => setError("Google Picker scripts could not be loaded."));
  }, [session]);

  async function selectFiles(files: SelectedDriveFile[]) {
    setBusy(true);
    setBusyLabel("Saving selected resources…");
    setError("");
    try {
      for (const file of files) {
        await api<void>("/api/v1/resources", {
          method: "POST",
          body: JSON.stringify({
            providerId: file.id,
            name: file.name,
            mimeType: file.mimeType,
            resourceType: file.mimeType === "application/vnd.google-apps.folder" ? "FOLDER" : "FILE",
            modifiedTime: file.modifiedDate ? new Date(file.modifiedDate).toISOString() : null,
            webViewLink: file.url ?? null,
            parentProviderId: null,
          }),
        });
      }
      await refresh();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The selected resources could not be saved.");
    } finally {
      setBusy(false);
    }
  }

  async function removeResource(providerId: string) {
    setBusy(true);
    setBusyLabel("Removing resource…");
    setError("");
    try {
      await api<void>(`/api/v1/resources/${encodeURIComponent(providerId)}`, { method: "DELETE" });
      await refresh();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The resource could not be removed.");
    } finally {
      setBusy(false);
    }
  }

  async function addTag(event: React.FormEvent<HTMLFormElement>, providerId: string) {
    event.preventDefault();
    const name = tagInputs[providerId]?.trim();
    if (!name) return;

    setBusy(true);
    setBusyLabel("Saving tag…");
    setError("");
    try {
      await api<ResourceTag>(`/api/v1/resources/${encodeURIComponent(providerId)}/tags`, {
        method: "POST",
        body: JSON.stringify({ name }),
      });
      setTagInputs((current) => ({ ...current, [providerId]: "" }));
      await refresh();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The tag could not be saved.");
    } finally {
      setBusy(false);
    }
  }

  async function removeTag(providerId: string, tagId: string) {
    setBusy(true);
    setBusyLabel("Removing tag…");
    setError("");
    try {
      await api<void>(
        `/api/v1/resources/${encodeURIComponent(providerId)}/tags/${encodeURIComponent(tagId)}`,
        { method: "DELETE" },
      );
      await refresh();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The tag could not be removed.");
    } finally {
      setBusy(false);
    }
  }

  async function disconnect() {
    if (!window.confirm("Disconnect and permanently delete your DriveAtlas account and selected-resource metadata? This does not delete anything in Google Drive.")) return;
    setBusy(true);
    setBusyLabel("Disconnecting…");
    setError("");
    try {
      await api<void>("/api/v1/account", { method: "DELETE" });
      await api<void>("/logout", { method: "POST" });
      window.location.assign("/");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Disconnect could not be completed.");
      setBusy(false);
    }
  }

  return (
    <main className="shell">
      <header className="topbar">
        <a className="brand" href="/" aria-label="DriveAtlas home">
          <span className="brand-mark">D</span>
          <span>DriveAtlas</span>
        </a>
        {session && <span className="account">{session.email}</span>}
      </header>

      <section className="hero">
        <div className="eyebrow"><span className="status-dot" /> RELEASE 1 · MANUAL TAGS</div>
        <h1>Your files.<br /><span>Your scope.</span></h1>
        <p className="intro">A calm, review-first space for the Google Drive files you choose. Nothing is scanned until you select it.</p>
        {loading ? <p className="muted">Checking your connection…</p> : session ? (
          <div className="actions">
            <button className="primary" disabled={busy || !pickerReady} onClick={() => {
              setError("");
              setBusy(true);
              setBusyLabel("Waiting for Google authorization…");
              void openDrivePicker(session.email, setBusyLabel)
                .then(async (files) => { if (files.length > 0) await selectFiles(files); })
                .catch((reason: unknown) =>
                  setError(reason instanceof Error ? reason.message : "Google Picker could not be opened."))
                .finally(() => setBusy(false));
            }}>
              <span aria-hidden="true">＋</span> {busy ? busyLabel : pickerReady ? "Select Drive files" : "Loading Google Picker…"}
            </button>
            <button className="quiet" disabled={busy} onClick={() => void disconnect()}>Disconnect</button>
          </div>
        ) : (
          <a className="primary login" href="/oauth2/authorization/google">Connect Google account <span aria-hidden="true">↗</span></a>
        )}
        {error && <p className="error" role="alert">{error}</p>}
      </section>

      <section className="library" aria-labelledby="library-title">
        <div className="section-heading">
          <div>
            <p className="eyebrow">YOUR SELECTED SCOPE</p>
            <h2 id="library-title">Selected resources</h2>
          </div>
          <span className="count">{resources.length.toString().padStart(2, "0")}</span>
        </div>
        {!session ? (
          <div className="empty"><span className="empty-icon">↗</span><p>Connect an account to begin. Your library only includes items chosen through Google Picker.</p></div>
        ) : resources.length === 0 ? (
          <div className="empty"><span className="empty-icon">⌁</span><p>Your library is empty. Select a few files or folders to set your scope.</p></div>
        ) : (
          <ul className="resource-list">
            {resources.map((resource) => (
              <li className="resource" key={resource.providerId}>
                <span className="file-icon" aria-hidden="true">{resource.resourceType === "FOLDER" ? "▰" : "▤"}</span>
                <div className="resource-info">
                  {safeDriveLink(resource.webViewLink)
                    ? <a href={safeDriveLink(resource.webViewLink)} target="_blank" rel="noreferrer">{resource.name}</a>
                    : <span className="resource-name">{resource.name}</span>}
                  <span>{resource.resourceType === "FOLDER" ? "Folder" : resource.mimeType}</span>
                  <div className="resource-tags" aria-label={`Tags for ${resource.name}`}>
                    {resource.tags.map((tag) => (
                      <span className="tag" key={tag.id}>
                        {tag.name}
                        <button
                          type="button"
                          disabled={busy}
                          aria-label={`Remove tag ${tag.name} from ${resource.name}`}
                          onClick={() => void removeTag(resource.providerId, tag.id)}
                        >×</button>
                      </span>
                    ))}
                    <form className="tag-form" onSubmit={(event) => void addTag(event, resource.providerId)}>
                      <input
                        aria-label={`New tag for ${resource.name}`}
                        maxLength={80}
                        value={tagInputs[resource.providerId] ?? ""}
                        onChange={(event) => setTagInputs((current) => ({
                          ...current,
                          [resource.providerId]: event.target.value,
                        }))}
                        placeholder="Add tag"
                        disabled={busy}
                      />
                      <button type="submit" disabled={busy || !tagInputs[resource.providerId]?.trim()}>+</button>
                    </form>
                  </div>
                </div>
                <span className="not-indexed">Selected · not indexed</span>
                <button className="remove" disabled={busy} aria-label={`Remove ${resource.name}`} onClick={() => void removeResource(resource.providerId)}>×</button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <footer>
        <span>Tags are user-applied; Drive file contents are not read.</span>
        <span>Original Drive files are never modified or deleted.</span>
      </footer>
    </main>
  );
}

export default App;
