export interface SelectedDriveFile {
  id: string;
  name: string;
  mimeType: string;
  modifiedDate?: string;
  url?: string;
}

interface PickerDocument {
  id: string;
  name: string;
  mimeType: string;
  lastEditedUtc?: number;
  url?: string;
}

interface PickerData {
  [key: string]: unknown;
  action?: string;
  docs?: PickerDocument[];
  error?: string;
  errorMessage?: string;
}

function isPickerDocument(value: unknown): value is PickerDocument {
  return typeof value === "object"
    && value !== null
    && "id" in value
    && typeof value.id === "string"
    && "name" in value
    && typeof value.name === "string"
    && "mimeType" in value
    && typeof value.mimeType === "string";
}

function isPickerDocumentList(value: unknown): value is PickerDocument[] {
  return Array.isArray(value) && value.every(isPickerDocument);
}

interface PickerView {
  setMode(mode: string): PickerView;
  setIncludeFolders(include: boolean): PickerView;
  setSelectFolderEnabled(enabled: boolean): PickerView;
}

interface PickerBuilder {
  addView(view: PickerView): PickerBuilder;
  enableFeature(feature: string): PickerBuilder;
  setAppId(id: string): PickerBuilder;
  setOAuthToken(token: string): PickerBuilder;
  setDeveloperKey(key: string): PickerBuilder;
  setCallback(callback: (data: PickerData) => void): PickerBuilder;
  build(): { setVisible(visible: boolean): void };
}

interface GooglePicker {
  Action: { PICKED: string; CANCEL: string; ERROR: string; LOADED: string };
  DocsViewMode: { LIST: string };
  Feature: { MULTISELECT_ENABLED: string };
  Response: { ACTION: string; DOCUMENTS: string };
  ViewId: { DOCS: string };
  DocsView: new (viewId: string) => PickerView;
  PickerBuilder: new () => PickerBuilder;
}

interface GoogleTokenResponse {
  access_token?: string;
  scope?: string;
  error?: string;
  error_description?: string;
}

interface GooglePopupError {
  type: string;
}

declare global {
  interface Window {
    gapi?: { load(module: string, callback: () => void): void };
    google?: {
      picker?: GooglePicker;
      accounts?: {
        oauth2?: {
          initTokenClient(options: {
            client_id: string;
            scope: string;
            login_hint: string;
            prompt: string;
            callback: (response: GoogleTokenResponse) => void;
            error_callback: (error: GooglePopupError) => void;
          }): { requestAccessToken(): void };
        };
      };
    };
  }
}

const DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file";
let pickerScript: Promise<void> | undefined;

function loadScript(src: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${src}"]`);
    if (existing) {
      if (existing.dataset.loaded === "true") resolve();
      else {
        existing.addEventListener("load", () => resolve(), { once: true });
        existing.addEventListener("error", () => reject(new Error("Google scripts could not be loaded.")), { once: true });
      }
      return;
    }
    const script = document.createElement("script");
    script.src = src;
    script.async = true;
    script.onload = () => {
      script.dataset.loaded = "true";
      resolve();
    };
    script.onerror = () => reject(new Error("Google scripts could not be loaded."));
    document.head.append(script);
  });
}

async function loadPicker(): Promise<void> {
  if (!pickerScript) {
    pickerScript = Promise.all([
      loadScript("https://apis.google.com/js/api.js"),
      loadScript("https://accounts.google.com/gsi/client"),
    ]).then(() => new Promise<void>((resolve) => window.gapi!.load("picker", resolve)));
  }
  await pickerScript;
}

export async function prepareDrivePicker(): Promise<void> {
  await loadPicker();
}

export async function openDrivePicker(
  accountEmail: string,
  onStatus?: (status: string) => void,
): Promise<SelectedDriveFile[]> {
  const clientId = import.meta.env.VITE_GOOGLE_CLIENT_ID;
  const apiKey = import.meta.env.VITE_GOOGLE_API_KEY;
  const appId = import.meta.env.VITE_GOOGLE_APP_ID;
  if (!clientId || !apiKey || !appId) {
    throw new Error("Google Picker is not configured. Set the client ID, API key, and project number.");
  }

  const oauth2 = window.google?.accounts?.oauth2;
  const picker = window.google?.picker;
  if (!oauth2 || !picker) throw new Error("Google Picker is still loading. Please try again.");

  return new Promise<SelectedDriveFile[]>((resolve, reject) => {
    let completed = false;
    const authorizationTimeout = window.setTimeout(() => {
      fail(new Error("Google authorization timed out. Close any open Google dialog and try again."));
    }, 120_000);

    function finish(action: () => void) {
      if (completed) return;
      completed = true;
      window.clearTimeout(authorizationTimeout);
      action();
    }

    function fail(reason: unknown) {
      finish(() => reject(reason instanceof Error ? reason : new Error("Google Picker could not be opened.")));
    }

    function succeed(files: SelectedDriveFile[]) {
      finish(() => resolve(files));
    }

    try {
      onStatus?.("Waiting for Google authorization…");
      const tokenClient = oauth2.initTokenClient({
        client_id: clientId,
        scope: DRIVE_FILE_SCOPE,
        login_hint: accountEmail,
        prompt: "",
        callback: (response) => {
          if (completed) return;
          window.clearTimeout(authorizationTimeout);
          if (response.error) {
            fail(new Error(response.error_description ?? `Google authorization failed: ${response.error}`));
            return;
          }
          if (!response.access_token) {
            fail(new Error("Google did not grant access to the file picker."));
            return;
          }
          if (response.scope && !response.scope.split(/\s+/).includes(DRIVE_FILE_SCOPE)) {
            fail(new Error("Google did not grant DriveAtlas the required file access. Reopen the picker and approve the Drive file-access request."));
            return;
          }

          try {
            onStatus?.("Choose files in the Google Picker…");
            const docsView = new picker.DocsView(picker.ViewId.DOCS)
              .setMode(picker.DocsViewMode.LIST)
              .setIncludeFolders(true)
              .setSelectFolderEnabled(true);
            const pickerInstance = new picker.PickerBuilder()
              .addView(docsView)
              .enableFeature(picker.Feature.MULTISELECT_ENABLED)
              .setAppId(appId)
              .setOAuthToken(response.access_token)
              .setDeveloperKey(apiKey)
              .setCallback((data) => {
                const action = data[picker.Response.ACTION];
                if (action === picker.Action.LOADED) {
                  return;
                }
                if (action === picker.Action.CANCEL) {
                  succeed([]);
                } else if (action === picker.Action.PICKED) {
                  const documents = data[picker.Response.DOCUMENTS];
                  if (!isPickerDocumentList(documents)) {
                    fail(new Error("Google Picker reported a selection without a document list."));
                    return;
                  }
                  succeed(documents.map((file) => ({
                    id: file.id,
                    name: file.name,
                    mimeType: file.mimeType,
                    modifiedDate: file.lastEditedUtc !== undefined
                      ? new Date(file.lastEditedUtc).toISOString()
                      : undefined,
                    url: file.url,
                  })));
                } else if (action === picker.Action.ERROR) {
                  const detail = data.error ?? data.errorMessage;
                  fail(new Error(detail
                      ? `Google Picker error: ${detail}`
                      : "Google Picker returned its ERROR action. Check the failed Picker request in DevTools Network."));
                } else {
                  fail(new Error(`Google Picker returned an unexpected action: ${String(action)}.`));
                }
              })
              .build();
            pickerInstance.setVisible(true);
          } catch (reason) {
            fail(reason);
          }
        },
        error_callback: (error) => {
          fail(new Error(error.type === "popup_closed"
            ? "Google authorization was closed before access was granted."
            : "Google's authorization popup could not be opened. Allow popups for this site and try again."));
        },
      });
      tokenClient.requestAccessToken();
    } catch (reason) {
      fail(reason);
    }
  });
}
