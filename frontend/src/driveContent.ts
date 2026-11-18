import type { SelectedDriveFile } from "./drivePicker";

const DRIVE_API = "https://www.googleapis.com/drive/v3";
const GOOGLE_DOC_MIME_TYPE = "application/vnd.google-apps.document";
const TEXT_MIME_TYPES = new Set(["text/plain", "text/markdown"]);
const MAX_EXTRACTED_BYTES = 500_000;

export function supportsTextExtraction(file: Pick<SelectedDriveFile, "name" | "mimeType">): boolean {
  const extensionFallback = ["application/octet-stream", "application/x-unknown"].includes(file.mimeType)
    && /\.(md|txt)$/i.test(file.name);
  return file.mimeType === GOOGLE_DOC_MIME_TYPE
    || TEXT_MIME_TYPES.has(file.mimeType)
    || extensionFallback;
}

export async function extractDriveText(
  file: Pick<SelectedDriveFile, "id" | "name" | "mimeType">,
  accessToken: string,
): Promise<string> {
  if (!supportsTextExtraction(file)) {
    throw new Error("Text extraction is supported for plain text, Markdown, and Google Docs only.");
  }
  const apiKey = import.meta.env.VITE_GOOGLE_API_KEY;
  if (!apiKey) throw new Error("Google Drive extraction is not configured. Set the Google API key.");

  const isGoogleDoc = file.mimeType === GOOGLE_DOC_MIME_TYPE;
  const endpoint = isGoogleDoc
    ? `${DRIVE_API}/files/${encodeURIComponent(file.id)}/export?mimeType=text%2Fplain`
    : `${DRIVE_API}/files/${encodeURIComponent(file.id)}?alt=media`;
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 60_000);
  try {
    let response: Response;
    try {
      response = await fetch(endpoint, {
        headers: {
          Authorization: `Bearer ${accessToken}`,
          "X-Goog-Api-Key": apiKey,
        },
        signal: controller.signal,
      });
    } catch (reason) {
      if (reason instanceof Error && reason.name === "AbortError") {
        throw new Error(`Text extraction timed out for "${file.name}".`);
      }
      throw new Error(`Could not read "${file.name}" from Google Drive.`);
    }

    if (!response.ok) {
      if (response.status === 401) throw new Error("Google Drive authorization expired. Restart text extraction.");
      if (response.status === 403) throw new Error(`Google Drive denied access to "${file.name}". Select it directly in Picker and try again.`);
      let detail = "";
      try {
        const body = await response.json() as { error?: { message?: unknown } };
        if (typeof body.error?.message === "string") detail = body.error.message;
      } catch {
        detail = "";
      }
      throw new Error(detail || `Google Drive could not export "${file.name}" (${response.status}).`);
    }

    const reader = response.body?.getReader();
    if (!reader) throw new Error(`Google Drive returned no readable content for "${file.name}".`);
    const chunks: Uint8Array[] = [];
    let byteCount = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      byteCount += value.byteLength;
      if (byteCount > MAX_EXTRACTED_BYTES) {
        await reader.cancel();
        throw new Error(`"${file.name}" exceeds the 500 KB text extraction limit.`);
      }
      chunks.push(value);
    }

    const bytes = new Uint8Array(byteCount);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    let text: string;
    try {
      text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    } catch {
      throw new Error(`"${file.name}" is not valid UTF-8 text and could not be extracted.`);
    }
    if (text.length > MAX_EXTRACTED_BYTES) {
      throw new Error(`"${file.name}" exceeds the text extraction limit.`);
    }
    return text;
  } finally {
    window.clearTimeout(timeout);
  }
}
