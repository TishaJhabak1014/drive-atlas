import type { SelectedDriveFile } from "./drivePicker";

const DRIVE_API = "https://www.googleapis.com/drive/v3";
const FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";
const PAGE_SIZE = 1000;
const MAX_DESCENDANTS = 5000;
const MAX_CONCURRENT_FOLDER_REQUESTS = 3;

interface DriveFile {
  id: string;
  name: string;
  mimeType: string;
  modifiedTime?: string;
  parents?: string[];
}

interface DriveFileList {
  files?: unknown[];
  nextPageToken?: string;
}

function isDriveFile(value: unknown): value is DriveFile {
  return typeof value === "object"
    && value !== null
    && "id" in value
    && typeof value.id === "string"
    && "name" in value
    && typeof value.name === "string"
    && "mimeType" in value
    && typeof value.mimeType === "string"
    && (!("modifiedTime" in value) || typeof value.modifiedTime === "string")
    && (!("parents" in value) || (Array.isArray(value.parents) && value.parents.every((parent) => typeof parent === "string")));
}

function toSelectedFile(file: DriveFile, fallbackParentId?: string): SelectedDriveFile {
  return {
    id: file.id,
    name: file.name,
    mimeType: file.mimeType,
    modifiedDate: file.modifiedTime,
    url: `https://drive.google.com/open?id=${encodeURIComponent(file.id)}`,
    parentId: file.parents?.[0] ?? fallbackParentId,
  };
}

async function driveJson<T>(url: string, accessToken: string, apiKey: string): Promise<T> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 30_000);
  try {
    let response: Response;
    try {
      response = await fetch(url, {
        headers: {
          Authorization: `Bearer ${accessToken}`,
          "X-Goog-Api-Key": apiKey,
        },
        signal: controller.signal,
      });
    } catch (reason) {
      if (reason instanceof Error && reason.name === "AbortError") {
        throw new Error("Google Drive did not respond in time. Try scanning the folder again.");
      }
      throw new Error("Could not reach the Google Drive API. Check the connection and try again.");
    }

    if (!response.ok) {
      let detail = "";
      try {
        const body = await response.json() as { error?: { message?: unknown } };
        if (typeof body.error?.message === "string") detail = body.error.message;
      } catch {
        detail = "";
      }
      if (response.status === 401) {
        throw new Error("Google Drive authorization expired. Start the folder scan again.");
      }
      if (response.status === 403) {
        throw new Error(detail || "Google Drive denied access. Enable the Drive API and confirm access to this folder.");
      }
      throw new Error(detail
        ? `Google Drive request failed (${response.status}): ${detail}`
        : `Google Drive request failed (${response.status}).`);
    }

    try {
      return await response.json() as T;
    } catch {
      throw new Error("Google Drive returned invalid metadata. No scan results were saved.");
    }
  } finally {
    window.clearTimeout(timeout);
  }
}

function driveFileUrl(fileId: string): string {
  return `${DRIVE_API}/files/${encodeURIComponent(fileId)}`;
}

export async function scanDriveFolder(
  rootId: string,
  accessToken: string,
  onProgress?: (scannedCount: number) => void,
): Promise<SelectedDriveFile[]> {
  const apiKey = import.meta.env.VITE_GOOGLE_API_KEY;
  if (!apiKey) throw new Error("Google Drive scanning is not configured. Set the Google API key.");

  const fields = "id,name,mimeType,modifiedTime,parents";
  const rootParams = new URLSearchParams({ fields, supportsAllDrives: "true" });
  const root = await driveJson<DriveFile>(
    `${driveFileUrl(rootId)}?${rootParams.toString()}`,
    accessToken,
    apiKey,
  );
  if (!isDriveFile(root) || root.mimeType !== FOLDER_MIME_TYPE) {
    throw new Error("Google Drive did not return the selected folder. Refresh the selection and try again.");
  }

  const results = [toSelectedFile(root)];
  const visitedFolders = new Set([rootId]);
  const seenFiles = new Set([rootId]);
  const foldersToScan = [rootId];
  const waitingWorkers: Array<() => void> = [];
  let activeFolders = 0;
  let scanFailure: unknown;

  function wakeWorker(): void {
    waitingWorkers.shift()?.();
  }

  function wakeAllWorkers(): void {
    while (waitingWorkers.length > 0) waitingWorkers.shift()?.();
  }

  async function scanFolder(folderId: string): Promise<void> {
    let pageToken: string | undefined;
    do {
      if (scanFailure) return;
      const params = new URLSearchParams({
        q: `'${folderId.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}' in parents and trashed = false`,
        pageSize: PAGE_SIZE.toString(),
        fields: `nextPageToken,files(${fields})`,
        supportsAllDrives: "true",
        includeItemsFromAllDrives: "true",
      });
      if (pageToken) params.set("pageToken", pageToken);

      const page = await driveJson<DriveFileList>(
        `${DRIVE_API}/files?${params.toString()}`,
        accessToken,
        apiKey,
      );
      if (scanFailure) return;
      if (!Array.isArray(page.files) || !page.files.every(isDriveFile)) {
        throw new Error("Google Drive returned invalid folder metadata. No scan results were saved.");
      }

      for (const file of page.files) {
        if (seenFiles.has(file.id)) continue;
        seenFiles.add(file.id);
        results.push(toSelectedFile(file, folderId));
        if (results.length - 1 > MAX_DESCENDANTS) {
          throw new Error(`This folder contains more than ${MAX_DESCENDANTS} items. Select a smaller folder to scan.`);
        }
        if (file.mimeType === FOLDER_MIME_TYPE && !visitedFolders.has(file.id)) {
          visitedFolders.add(file.id);
          foldersToScan.push(file.id);
          wakeWorker();
        }
      }

      onProgress?.(results.length - 1);
      pageToken = page.nextPageToken;
    } while (pageToken);
  }

  async function worker(): Promise<void> {
    while (!scanFailure) {
      const folderId = foldersToScan.shift();
      if (folderId) {
        activeFolders += 1;
        try {
          await scanFolder(folderId);
        } catch (reason) {
          scanFailure = reason;
        } finally {
          activeFolders -= 1;
          if (scanFailure) wakeAllWorkers();
          else if (foldersToScan.length > 0) wakeWorker();
          else if (activeFolders === 0) wakeAllWorkers();
        }
        continue;
      }
      if (activeFolders === 0) return;
      await new Promise<void>((resolve) => waitingWorkers.push(resolve));
    }
  }

  await Promise.all(Array.from({ length: MAX_CONCURRENT_FOLDER_REQUESTS }, () => worker()));
  if (scanFailure) throw scanFailure;

  return results;
}
