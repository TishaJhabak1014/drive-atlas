import { useCallback, useEffect, useState, type ReactNode } from "react";
import { extractDriveText, supportsTextExtraction } from "./driveContent";
import {
  MAX_COMPOSER_SOURCES,
  MAX_COMPOSER_TEXT_LENGTH,
  renderConsolidationMarkdown,
  safeDraftFilename,
  type CompositionMode,
  type CompositionResult,
  type DraftSource,
  validateDraftSources,
} from "./draftComposer";
import { scanDriveFolder } from "./driveLibrary";
import {
  openDrivePicker,
  prepareDrivePicker,
  requestDriveAccessToken,
  verifyDriveAccount,
  type SelectedDriveFile,
} from "./drivePicker";

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
  parentProviderId?: string | null;
  explicitlySelected: boolean;
  selectedAt: string;
  metadataRefreshedAt: string | null;
  contentExtractedAt: string | null;
  hasStoredText: boolean;
  indexingStatus: string;
  tags: ResourceTag[];
}

interface ResourceTag {
  id: string;
  name: string;
}

interface TagSuggestion {
  name: string;
  confidence: number;
  evidence: string;
}

interface StoredContent {
  text: string;
  extractedAt: string;
}

interface ComposerDraft {
  filename: string;
  content: string;
  sources: DraftSource[];
  mode: CompositionMode;
}

const MAX_SUGGESTION_TEXT_LENGTH = 30_000;

class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = "ApiError";
  }
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
    throw new ApiError(detail?.detail ?? detail?.title ?? `Request failed (${response.status}).`, response.status);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
};

function safeDriveLink(value?: string): string | undefined {
  return value && /^https:\/\/(drive|docs)\.google\.com\//i.test(value) ? value : undefined;
}

function belongsToFolder(
  resource: SelectedResource,
  folderId: string,
  resourcesById: ReadonlyMap<string, SelectedResource>,
): boolean {
  const visited = new Set<string>();
  let parentId = resource.parentProviderId;
  while (parentId && !visited.has(parentId)) {
    if (parentId === folderId) return true;
    visited.add(parentId);
    parentId = resourcesById.get(parentId)?.parentProviderId ?? null;
  }
  return resource.providerId === folderId;
}

function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [resources, setResources] = useState<SelectedResource[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [busyLabel, setBusyLabel] = useState("Working…");
  const [pickerReady, setPickerReady] = useState(false);
  const [error, setError] = useState("");
  const [successNotice, setSuccessNotice] = useState("");
  const [tagInputs, setTagInputs] = useState<Record<string, string>>({});
  const [retainExtractedText, setRetainExtractedText] = useState<Record<string, boolean>>({});
  const [extractedText, setExtractedText] = useState<Record<string, string>>({});
  const [tagSuggestions, setTagSuggestions] = useState<Record<string, TagSuggestion[]>>({});
  const [draftSourceIds, setDraftSourceIds] = useState<Set<string>>(() => new Set());
  const [compositionMode, setCompositionMode] = useState<CompositionMode>("SUMMARIZE");
  const [composerDraft, setComposerDraft] = useState<ComposerDraft | null>(null);
  const [folderFilter, setFolderFilter] = useState("");
  const [tagFilter, setTagFilter] = useState("");
  const [typeFilter, setTypeFilter] = useState("all");
  const [scanningFolderId, setScanningFolderId] = useState("");
  const [collapsedFolders, setCollapsedFolders] = useState<Set<string>>(() => new Set());
  const selectedDraftResources = resources.filter((resource) =>
    draftSourceIds.has(resource.providerId)
    && resource.resourceType === "FILE"
    && supportsTextExtraction({ name: resource.name, mimeType: resource.mimeType }));

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const current = await api<Session>("/api/v1/session");
      setSession(current);
      setResources(await api<SelectedResource[]>("/api/v1/resources"));
    } catch (reason) {
      if (reason instanceof Error && !(reason instanceof ApiError && reason.status === 401)) setError(reason.message);
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
            parentProviderId: file.parentId ?? null,
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

  async function scanFolder(resource: SelectedResource) {
    if (!session) return;
    setBusy(true);
    setBusyLabel(`Authorizing ${resource.name}…`);
    setScanningFolderId(resource.providerId);
    setError("");
    setSuccessNotice("");
    try {
      await api<Session>("/api/v1/session");
      const accessToken = await requestDriveAccessToken(session.email, setBusyLabel, "folder-scan");
      await verifyDriveAccount(accessToken, session.email);
      const scannedResources = await scanDriveFolder(
        resource.providerId,
        accessToken,
        (count) => setBusyLabel(`Scanning folder… ${count} items found`),
      );
      setBusyLabel(`Saving metadata for ${scannedResources.length - 1} items…`);
      await api<void>(`/api/v1/resources/${encodeURIComponent(resource.providerId)}/scan`, {
        method: "POST",
        body: JSON.stringify({
          resources: scannedResources.map((file) => ({
            providerId: file.id,
            name: file.name,
            mimeType: file.mimeType,
            resourceType: file.mimeType === "application/vnd.google-apps.folder" ? "FOLDER" : "FILE",
            modifiedTime: file.modifiedDate ?? null,
            webViewLink: file.url ?? null,
            parentProviderId: file.parentId ?? null,
          })),
        }),
      });
      const itemCount = scannedResources.length - 1;
      setSuccessNotice(itemCount === 0
        ? `Scan completed for "${resource.name}", but no descendant items were returned. The folder may be empty or its contents may not be accessible.`
        : `Scan complete: ${itemCount} descendant ${itemCount === 1 ? "item" : "items"} saved from "${resource.name}".`);
      await refresh();
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        setSession(null);
        setResources([]);
        setError("Your DriveAtlas sign-in expired. Connect your account again, then restart the folder scan.");
      } else {
        setError(reason instanceof Error ? reason.message : "The folder could not be scanned.");
      }
    } finally {
      setBusy(false);
      setScanningFolderId("");
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

  async function extractText(resource: SelectedResource) {
    if (!session) return;
    setBusy(true);
    setBusyLabel(`Authorizing access to ${resource.name}…`);
    setError("");
    setSuccessNotice("");
    try {
      const accessToken = await requestDriveAccessToken(session.email, setBusyLabel, "content-extract");
      await verifyDriveAccount(accessToken, session.email);
      setBusyLabel(`Extracting text from ${resource.name}…`);
      const text = await extractDriveText({
        id: resource.providerId,
        name: resource.name,
        mimeType: resource.mimeType,
      }, accessToken);
      const shouldRetain = retainExtractedText[resource.providerId] ?? true;
      if (shouldRetain) {
        setBusyLabel(`Saving extracted text for ${resource.name}…`);
        await api<void>(`/api/v1/resources/${encodeURIComponent(resource.providerId)}/content`, {
          method: "PUT",
          body: JSON.stringify({ text }),
        });
      } else if (resource.hasStoredText) {
        setBusyLabel(`Removing the previous saved text for ${resource.name}…`);
        await api<void>(`/api/v1/resources/${encodeURIComponent(resource.providerId)}/content`, {
          method: "DELETE",
        });
      }
      setExtractedText((current) => ({ ...current, [resource.providerId]: text }));
      setTagSuggestions((current) => ({ ...current, [resource.providerId]: [] }));
      setSuccessNotice(shouldRetain
        ? `Extracted text from "${resource.name}" and saved it for later.`
        : `Extracted text from "${resource.name}" for this browser session only.`);
      await refresh();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : `Text could not be extracted from "${resource.name}".`);
    } finally {
      setBusy(false);
    }
  }

  async function loadSavedText(resource: SelectedResource) {
    setBusy(true);
    setBusyLabel(`Loading saved text for ${resource.name}…`);
    setError("");
    try {
      const content = await api<StoredContent>(
        `/api/v1/resources/${encodeURIComponent(resource.providerId)}/content`,
      );
      setExtractedText((current) => ({ ...current, [resource.providerId]: content.text }));
      setTagSuggestions((current) => ({ ...current, [resource.providerId]: [] }));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : `Saved text for "${resource.name}" could not be loaded.`);
    } finally {
      setBusy(false);
    }
  }

  async function forgetSavedText(resource: SelectedResource) {
    if (!window.confirm(`Permanently forget the saved extracted text for "${resource.name}"?`)) return;
    setBusy(true);
    setBusyLabel(`Forgetting saved text for ${resource.name}…`);
    setError("");
    try {
      await api<void>(`/api/v1/resources/${encodeURIComponent(resource.providerId)}/content`, {
        method: "DELETE",
      });
      setExtractedText((current) => {
        const next = { ...current };
        delete next[resource.providerId];
        return next;
      });
      setTagSuggestions((current) => ({ ...current, [resource.providerId]: [] }));
      setSuccessNotice(`Saved extracted text for "${resource.name}" was deleted.`);
      await refresh();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : `Saved text for "${resource.name}" could not be deleted.`);
    } finally {
      setBusy(false);
    }
  }

  async function requestTagSuggestions(resource: SelectedResource) {
    const text = extractedText[resource.providerId];
    if (!text) {
      setError("Extract or load saved text before requesting AI tag suggestions.");
      return;
    }
    if (!window.confirm(
      "Send up to 30,000 characters of this document to Google Gemini for tag suggestions? Google may use content sent through its free tier to improve Google products. Do not send confidential or sensitive text.",
    )) return;

    setBusy(true);
    setBusyLabel("Requesting tag suggestions from Gemini…");
    setError("");
    setSuccessNotice("");
    try {
      const response = await api<TagSuggestion[]>(
        `/api/v1/resources/${encodeURIComponent(resource.providerId)}/tag-suggestions`,
        {
          method: "POST",
          body: JSON.stringify({ text: text.slice(0, MAX_SUGGESTION_TEXT_LENGTH) }),
        },
      );
      setTagSuggestions((current) => ({ ...current, [resource.providerId]: response }));
      if (text.length > MAX_SUGGESTION_TEXT_LENGTH) {
        setSuccessNotice("Suggestions use only the first 30,000 characters of the extracted text.");
      } else if (response.length === 0) {
        setSuccessNotice("Gemini did not return any tag suggestions for this text.");
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Gemini tag suggestions could not be generated.");
    } finally {
      setBusy(false);
    }
  }

  function toggleDraftSource(providerId: string) {
    const isSelected = draftSourceIds.has(providerId);
    if (!isSelected && selectedDraftResources.length >= MAX_COMPOSER_SOURCES) {
      setError(`A draft can include at most ${MAX_COMPOSER_SOURCES} source files.`);
      return;
    }
    setError("");
    setDraftSourceIds((current) => {
      const next = new Set(current);
      if (next.has(providerId)) next.delete(providerId);
      else next.add(providerId);
      return next;
    });
  }

  async function createComposerDraft() {
    if (selectedDraftResources.length < 2) {
      setError("Select at least two supported files to compose a draft.");
      return;
    }
    if (composerDraft && !window.confirm("Replace the current local draft and discard its edits?")) return;
    const missingText = selectedDraftResources.filter((resource) =>
      extractedText[resource.providerId] === undefined && !resource.hasStoredText);
    if (missingText.length > 0) {
      setError(`Extract text first for: ${missingText.map((resource) => resource.name).join(", ")}.`);
      return;
    }

    setBusy(true);
    setBusyLabel("Preparing source texts…");
    setError("");
    setSuccessNotice("");
    try {
      const sources = await Promise.all(selectedDraftResources.map(async (resource) => {
        const text = extractedText[resource.providerId] ?? (await api<StoredContent>(
          `/api/v1/resources/${encodeURIComponent(resource.providerId)}/content`,
        )).text;
        return {
          providerId: resource.providerId,
          name: resource.name,
          text,
        };
      }));
      const citedSources = sources.map((source, index) => ({
        ...source,
        sourceId: `S${index + 1}`,
      }));
      const validationError = validateDraftSources(citedSources);
      if (validationError) throw new Error(validationError);
      const totalCharacters = citedSources.reduce((total, source) => total + source.text.length, 0);
      const sourceNames = citedSources
        .map((source) => `• ${source.name.replace(/\s+/g, " ").trim().slice(0, 120)}`)
        .join("\n");
      const modeDescription = compositionMode === "FULL"
        ? "Preserve and refine unique content, removing only meaningfully repeated material."
        : "Create a concise semantic summary of the selected sources.";
      if (!window.confirm(
        `${modeDescription}\n\nSelected files:\n${sourceNames}\n\nSend ${citedSources.length} source texts (${totalCharacters.toLocaleString()} characters) to Google Gemini? Gemini may receive one additional request if its response needs format or citation correction. In Merge-in-full mode, conflicting claims are flagged rather than fact-checked externally. Free-tier content may be used to improve Google products. Do not send confidential or sensitive text.`,
      )) return;
      setBusyLabel("Consolidating sources with Gemini…");
      const result = await api<CompositionResult>("/api/v1/compositions", {
        method: "POST",
        body: JSON.stringify({
          mode: compositionMode,
          sources: citedSources.map(({ providerId, text }) => ({ providerId, text })),
        }),
      });
      setComposerDraft({
        filename: result.title || "DriveAtlas draft",
        content: renderConsolidationMarkdown(result, citedSources, compositionMode),
        sources: citedSources,
        mode: compositionMode,
      });
      setDraftSourceIds(new Set());
      setSuccessNotice(
        compositionMode === "FULL"
          ? "Gemini created a detail-preserving merge. Review the cited evidence and conflict notes before downloading."
          : "Gemini created a concise summary. Review the cited evidence and merge notes before downloading.",
      );
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The draft could not be prepared.");
    } finally {
      setBusy(false);
    }
  }

  function downloadComposerDraft() {
    if (!composerDraft) return;
    const url = URL.createObjectURL(new Blob([composerDraft.content], { type: "text/markdown;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = `${safeDraftFilename(composerDraft.filename)}.md`;
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
    setSuccessNotice(`Downloaded "${link.download}". Original Drive files were not changed.`);
  }

  async function acceptTagSuggestion(resource: SelectedResource, suggestion: TagSuggestion) {
    setBusy(true);
    setBusyLabel(`Applying tag "${suggestion.name}"…`);
    setError("");
    try {
      await api<ResourceTag>(`/api/v1/resources/${encodeURIComponent(resource.providerId)}/tags`, {
        method: "POST",
        body: JSON.stringify({ name: suggestion.name }),
      });
      setTagSuggestions((current) => ({
        ...current,
        [resource.providerId]: (current[resource.providerId] ?? [])
          .filter((item) => item.name !== suggestion.name),
      }));
      await refresh();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : `Tag "${suggestion.name}" could not be applied.`);
    } finally {
      setBusy(false);
    }
  }

  function dismissTagSuggestion(providerId: string, name: string) {
    setTagSuggestions((current) => ({
      ...current,
      [providerId]: (current[providerId] ?? []).filter((suggestion) => suggestion.name !== name),
    }));
  }

  async function disconnect() {
    if (!window.confirm("Disconnect and permanently delete your DriveAtlas account, selected-resource metadata, tags, and any saved extracted text? This also closes the current local draft. It does not delete anything in Google Drive.")) return;
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

  const resourcesById = new Map(resources.map((resource) => [resource.providerId, resource]));
  const folders = resources.filter((resource) => resource.resourceType === "FOLDER");
  const tags = [...new Set(resources.flatMap((resource) => resource.tags.map((tag) => tag.name)))]
    .sort((left, right) => left.localeCompare(right));
  const fileTypes = [...new Set(resources
    .filter((resource) => resource.resourceType === "FILE")
    .map((resource) => resource.mimeType))]
    .sort((left, right) => left.localeCompare(right));
  const visibleResources = resources.filter((resource) => {
    const matchesFolder = !folderFilter || belongsToFolder(resource, folderFilter, resourcesById);
    const matchesTag = !tagFilter || resource.tags.some((tag) => tag.name === tagFilter);
    const matchesType = typeFilter === "all"
      || (typeFilter === "folders"
        ? resource.resourceType === "FOLDER"
        : resource.mimeType === typeFilter);
    return matchesFolder && matchesTag && matchesType;
  });
  const visibleResourceIds = new Set(visibleResources.map((resource) => resource.providerId));
  const childrenByParent = new Map<string, SelectedResource[]>();
  for (const resource of visibleResources) {
    const parentId = resource.parentProviderId;
    if (!parentId || !visibleResourceIds.has(parentId)) continue;
    const siblings = childrenByParent.get(parentId) ?? [];
    siblings.push(resource);
    childrenByParent.set(parentId, siblings);
  }
  const rootResources = visibleResources.filter((resource) =>
    !resource.parentProviderId || !visibleResourceIds.has(resource.parentProviderId));
  const sortResources = (items: SelectedResource[]) => items.sort((left, right) =>
    left.resourceType.localeCompare(right.resourceType) || left.name.localeCompare(right.name));

  function toggleFolder(providerId: string) {
    setCollapsedFolders((current) => {
      const next = new Set(current);
      if (next.has(providerId)) next.delete(providerId);
      else next.add(providerId);
      return next;
    });
  }

  function renderResource(resource: SelectedResource, depth = 0): ReactNode {
    const children = sortResources(childrenByParent.get(resource.providerId) ?? []);
    const isCollapsed = collapsedFolders.has(resource.providerId);
    const isExpandedFolder = resource.resourceType === "FOLDER" && children.length > 0;
    const savedText = extractedText[resource.providerId];
    const suggestions = tagSuggestions[resource.providerId] ?? [];
    const hasComposerText = resource.hasStoredText || savedText !== undefined;
    const supportsExtraction = resource.resourceType === "FILE"
      && supportsTextExtraction({ name: resource.name, mimeType: resource.mimeType });
    return (
      <li className="resource-node" key={resource.providerId}>
        <div className="resource" style={{ paddingLeft: `${depth * 22 + 4}px` }}>
          {isExpandedFolder ? (
            <button
              type="button"
              className="tree-toggle"
              aria-label={`${isCollapsed ? "Expand" : "Collapse"} folder ${resource.name}`}
              aria-expanded={!isCollapsed}
              onClick={() => toggleFolder(resource.providerId)}
            >{isCollapsed ? "▸" : "▾"}</button>
          ) : <span className="tree-spacer" aria-hidden="true" />}
          <span className="file-icon" aria-hidden="true">{resource.resourceType === "FOLDER" ? "▰" : "▤"}</span>
          <div className="resource-info">
            {safeDriveLink(resource.webViewLink)
              ? <a href={safeDriveLink(resource.webViewLink)} target="_blank" rel="noreferrer">{resource.name}</a>
              : <span className="resource-name">{resource.name}</span>}
            <span>{resource.resourceType === "FOLDER" ? "Folder" : resource.mimeType}</span>
            {resource.metadataRefreshedAt
              ? <time className="metadata-time" dateTime={resource.metadataRefreshedAt}>
                  Metadata refreshed {new Date(resource.metadataRefreshedAt).toLocaleString()}
                </time>
              : <span className="metadata-time">Metadata not refreshed yet</span>}
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
            {supportsExtraction && (
              <label className="compose-source">
                <input
                  type="checkbox"
                  checked={draftSourceIds.has(resource.providerId)}
                  disabled={busy || (!hasComposerText && !draftSourceIds.has(resource.providerId))
                    || (!draftSourceIds.has(resource.providerId)
                    && selectedDraftResources.length >= MAX_COMPOSER_SOURCES)}
                  onChange={() => toggleDraftSource(resource.providerId)}
                />
                {hasComposerText ? "Include in Gemini consolidation" : "Extract text for consolidation"}
              </label>
            )}
            {supportsExtraction && (
              <div className="content-tools">
                <div className="content-actions">
                  <label className="retain-text">
                    <input
                      type="checkbox"
                      checked={retainExtractedText[resource.providerId] ?? true}
                      disabled={busy}
                      onChange={(event) => setRetainExtractedText((current) => ({
                        ...current,
                        [resource.providerId]: event.target.checked,
                      }))}
                    />
                    Save extracted text for later
                  </label>
                  <button
                    type="button"
                    className="content-action"
                    disabled={busy}
                    onClick={() => void extractText(resource)}
                  >{resource.hasStoredText ? "Extract / refresh text" : "Extract text"}</button>
                  {resource.hasStoredText && savedText === undefined && (
                    <button
                      type="button"
                      className="content-action"
                      disabled={busy}
                      onClick={() => void loadSavedText(resource)}
                    >View saved text</button>
                  )}
                  {resource.hasStoredText && (
                    <button
                      type="button"
                      className="content-action content-forget"
                      disabled={busy}
                      onClick={() => void forgetSavedText(resource)}
                    >Forget saved text</button>
                  )}
                </div>
                <small className="content-note">
                  Saving lets you revisit this text and use it with documents added in future scans. Uncheck before extracting to keep the new text in this browser session only and remove any previous saved copy.
                </small>
                <small className="content-note">
                  Google grants read-only access across Drive. DriveAtlas extracts only files in your selected resources and scanned folder scopes.
                </small>
                {resource.hasStoredText && resource.contentExtractedAt && (
                  <time className="metadata-time" dateTime={resource.contentExtractedAt}>
                    Text saved {new Date(resource.contentExtractedAt).toLocaleString()}
                  </time>
                )}
                {savedText !== undefined && (
                  <details className="text-preview" open>
                    <summary>Extracted text</summary>
                    <pre>{savedText || "(This file contains no text.)"}</pre>
                  </details>
                )}
                {savedText !== undefined && (
                  <div className="ai-suggestions">
                    <p className="ai-disclosure">
                      Optional Gemini suggestions send up to 30,000 characters to Google. Free-tier content may be used to improve Google products; do not send confidential or sensitive text.
                    </p>
                    <button
                      type="button"
                      className="content-action"
                      disabled={busy || savedText.length === 0}
                      onClick={() => void requestTagSuggestions(resource)}
                    >Suggest tags with Gemini</button>
                    {suggestions.length > 0 && (
                      <ul className="suggestion-list">
                        {suggestions.map((suggestion) => (
                          <li key={suggestion.name}>
                            <div>
                              <strong>{suggestion.name}</strong>
                              <span>{Math.round(suggestion.confidence * 100)}% confidence</span>
                              <blockquote>{suggestion.evidence}</blockquote>
                            </div>
                            <div className="suggestion-actions">
                              <button
                                type="button"
                                disabled={busy}
                                onClick={() => void acceptTagSuggestion(resource, suggestion)}
                              >Accept</button>
                              <button
                                type="button"
                                disabled={busy}
                                onClick={() => dismissTagSuggestion(resource.providerId, suggestion.name)}
                              >Dismiss</button>
                            </div>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                )}
              </div>
            )}
          </div>
          <span className="not-indexed">
            {resource.explicitlySelected
              ? resource.hasStoredText ? "Selected · text saved" : "Selected · not indexed"
              : resource.hasStoredText ? "In selected folder · text saved" : "In selected folder · metadata only"}
          </span>
          {resource.resourceType === "FOLDER" && resource.explicitlySelected && (
            <button
              className="scan-folder"
              disabled={busy}
              onClick={() => void scanFolder(resource)}
            >{busy && scanningFolderId === resource.providerId ? busyLabel : "Scan folder"}</button>
          )}
          {resource.explicitlySelected && (
            <button
              className="remove"
              disabled={busy}
              aria-label={`Remove ${resource.name}`}
              onClick={() => void removeResource(resource.providerId)}
            >×</button>
          )}
        </div>
        {isExpandedFolder && !isCollapsed && (
          <ul className="resource-children">
            {children.map((child) => renderResource(child, depth + 1))}
          </ul>
        )}
      </li>
    );
  }

  const hasActiveFilters = Boolean(folderFilter || tagFilter || typeFilter !== "all");

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
        <div className="eyebrow"><span className="status-dot" /> RELEASE 3 PREVIEW · AI-ASSISTED COMPOSITION</div>
        <h1>Your files.<br /><span>Your scope.</span></h1>
        <p className="intro">A calm, review-first space for the Google Drive files you choose. Organize sources, review their text, and consolidate related ideas without changing originals.</p>
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
        {successNotice && <p className="success" role="status">{successNotice}</p>}
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
          <>
            <div className="library-filters" aria-label="Filter selected resources">
              <label>
                <span>Folder</span>
                <select value={folderFilter} onChange={(event) => setFolderFilter(event.target.value)}>
                  <option value="">All folders</option>
                  {folders.map((folder) => <option key={folder.providerId} value={folder.providerId}>{folder.name}</option>)}
                </select>
              </label>
              <label>
                <span>Tag</span>
                <select value={tagFilter} onChange={(event) => setTagFilter(event.target.value)}>
                  <option value="">All tags</option>
                  {tags.map((tag) => <option key={tag} value={tag}>{tag}</option>)}
                </select>
              </label>
              <label>
                <span>File type</span>
                <select value={typeFilter} onChange={(event) => setTypeFilter(event.target.value)}>
                  <option value="all">All types</option>
                  <option value="folders">Folders</option>
                  {fileTypes.map((mimeType) => <option key={mimeType} value={mimeType}>{mimeType}</option>)}
                </select>
              </label>
              {hasActiveFilters && (
                <button
                  type="button"
                  className="clear-filters"
                  onClick={() => {
                    setFolderFilter("");
                    setTagFilter("");
                    setTypeFilter("all");
                  }}
                >Clear filters</button>
              )}
              <span className="filter-count">{visibleResources.length} shown</span>
            </div>
            <fieldset className="merge-modes" disabled={busy}>
              <legend>Choose a merge mode</legend>
              <label>
                <input
                  type="radio"
                  name="composition-mode"
                  checked={compositionMode === "SUMMARIZE"}
                  onChange={() => setCompositionMode("SUMMARIZE")}
                />
                <span><strong>Merge-summarize</strong><small>Create a concise overview that combines the main ideas.</small></span>
              </label>
              <label>
                <input
                  type="radio"
                  name="composition-mode"
                  checked={compositionMode === "FULL"}
                  onChange={() => setCompositionMode("FULL")}
                />
                <span><strong>Merge-in-full</strong><small>Preserve and refine unique details; remove only overlapping content. Contradictions are flagged, not externally fact-checked.</small></span>
              </label>
            </fieldset>
            <div className="compose-toolbar">
              <div>
                <strong>Select sources for this merge</strong>
                <span>Choose 2–{MAX_COMPOSER_SOURCES} files with extracted text. Up to {MAX_COMPOSER_TEXT_LENGTH.toLocaleString()} characters are sent after confirmation. Selection clears after a successful merge.</span>
              </div>
              <div className="compose-actions">
                <span>{selectedDraftResources.length} selected</span>
                <button
                  type="button"
                  className="clear-filters"
                  disabled={busy || selectedDraftResources.length === 0}
                  onClick={() => setDraftSourceIds(new Set())}
                >Clear selection</button>
                <button
                  type="button"
                  className="content-action"
                  disabled={busy || selectedDraftResources.length < 2}
                  onClick={() => void createComposerDraft()}
                >{compositionMode === "FULL" ? "Merge in full" : "Merge-summarize"} ({selectedDraftResources.length})</button>
              </div>
            </div>
            {composerDraft && (
              <section className="composer" aria-labelledby="composer-title">
                <div className="composer-heading">
                  <div>
                    <p className="eyebrow">AI-ASSISTED · REVIEW BEFORE SHARING</p>
                    <h3 id="composer-title">{composerDraft.mode === "FULL" ? "Merge-in-full draft" : "Merge-summarize draft"}</h3>
                  </div>
                  <button
                    type="button"
                    className="clear-filters"
                    onClick={() => {
                      if (window.confirm("Close and discard this local draft? It is not saved by DriveAtlas.")) {
                        setComposerDraft(null);
                      }
                    }}
                  >Close draft</button>
                </div>
                <label className="draft-filename">
                  <span>Download filename</span>
                  <input
                    maxLength={120}
                    value={composerDraft.filename}
                    onChange={(event) => setComposerDraft((current) => current
                      ? { ...current, filename: event.target.value }
                      : current)}
                  />
                </label>
                <p className="content-note">
                  Review each citation, exact evidence quote, and merge note. Edit the Markdown before downloading. The draft is not saved by DriveAtlas or written to Drive.
                </p>
                <textarea
                  className="draft-editor"
                  aria-label="Source-cited Markdown draft"
                  value={composerDraft.content}
                  onChange={(event) => setComposerDraft((current) => current
                    ? { ...current, content: event.target.value }
                    : current)}
                  spellCheck
                />
                <div className="composer-actions">
                  <span>{composerDraft.sources.length} sources · {composerDraft.content.length.toLocaleString()} characters</span>
                  <button
                    type="button"
                    className="content-action"
                    disabled={!composerDraft.content.trim()}
                    onClick={downloadComposerDraft}
                  >Download Markdown</button>
                </div>
              </section>
            )}
            {visibleResources.length === 0 ? (
              <div className="empty"><span className="empty-icon">⌕</span><p>No selected resources match these filters.</p></div>
            ) : <ul className="resource-list">
              {sortResources(rootResources).map((resource) => renderResource(resource))}
            </ul>}
          </>
        )}
      </section>

      <footer>
        <span>Folder scans read metadata only. Gemini processes selected texts only after confirmation; originals remain unchanged.</span>
        <span><a href="/privacy.html">Privacy notice</a> · <a href="mailto:tishajhabak22@gmail.com">Support</a> · Original Drive files are never modified or deleted.</span>
      </footer>
    </main>
  );
}

export default App;
