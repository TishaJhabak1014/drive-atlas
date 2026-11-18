export interface DraftSource {
  sourceId: string;
  providerId: string;
  name: string;
  text: string;
}

export interface CompositionEvidence {
  sourceId: string;
  quote: string;
}

export interface CompositionParagraph {
  text: string;
  evidence: CompositionEvidence[];
}

export interface CompositionSection {
  heading: string;
  paragraphs: CompositionParagraph[];
}

export interface CompositionNote {
  category: "duplicate" | "unique" | "structure" | "correction" | "conflict";
  summary: string;
  evidence: CompositionEvidence[];
}

export interface CompositionResult {
  title: string;
  sections: CompositionSection[];
  notes: CompositionNote[];
}

export type CompositionMode = "SUMMARIZE" | "FULL";

export const MAX_COMPOSER_SOURCES = 10;
export const MAX_COMPOSER_TEXT_LENGTH = 60_000;

export function validateDraftSources(sources: DraftSource[]): string | undefined {
  if (sources.length < 2) return "Select at least two supported files to compose a draft.";
  if (sources.length > MAX_COMPOSER_SOURCES) {
    return `A draft can include at most ${MAX_COMPOSER_SOURCES} source files.`;
  }
  const totalCharacters = sources.reduce((total, source) => total + source.text.length, 0);
  if (totalCharacters > MAX_COMPOSER_TEXT_LENGTH) {
    return `Selected source text exceeds the ${MAX_COMPOSER_TEXT_LENGTH.toLocaleString()} character draft limit. Choose fewer or shorter files.`;
  }
  if (sources.every((source) => source.text.trim().length === 0)) {
    return "The selected files contain no extracted text to compose.";
  }
  return undefined;
}

function escapeMarkdownLabel(value: string): string {
  return value.replace(/[\r\n]/g, " ").replace(/[\\[\]()]/g, "\\$&");
}

function escapeMarkdownHeading(value: string): string {
  return value.replace(/[\\#`<>]/g, "\\$&").replace(/\r?\n/g, " ");
}

function sourceUrl(source: DraftSource): string {
  const providerId = encodeURIComponent(source.providerId).replace(/[!'()*]/g, (character) =>
    `%${character.charCodeAt(0).toString(16).padStart(2, "0").toUpperCase()}`);
  return `https://drive.google.com/open?id=${providerId}`;
}

export function renderConsolidationMarkdown(
  result: CompositionResult,
  sources: DraftSource[],
  mode: CompositionMode = "SUMMARIZE",
): string {
  const sourcesById = new Map(sources.map((source) => [source.sourceId, source]));
  const evidenceReferences = new Map<string, { id: string; source: DraftSource; quote: string }>();

  function referenceFor(evidence: CompositionEvidence): string {
    const source = sourcesById.get(evidence.sourceId);
    if (!source) throw new Error("The consolidation cited an unknown source.");
    const key = `${evidence.sourceId}\u0000${evidence.quote}`;
    let reference = evidenceReferences.get(key);
    if (!reference) {
      reference = { id: `e${evidenceReferences.size + 1}`, source, quote: evidence.quote };
      evidenceReferences.set(key, reference);
    }
    return `[^${reference.id}]`;
  }

  const sections = result.sections.map((section) => [
    `## ${escapeMarkdownHeading(section.heading)}`,
    "",
    ...section.paragraphs.flatMap((paragraph) => [
      `${paragraph.text} ${paragraph.evidence.map(referenceFor).join(" ")}`,
      "",
    ]),
  ].join("\n"));
  const notes = result.notes.length === 0 ? [] : [
    "## Merge notes",
    "",
    ...result.notes.map((note) => {
      const citations = note.evidence.map(referenceFor).join(" ");
      return `- **${note.category}:** ${note.summary} ${citations}`;
    }),
    "",
  ];
  const sourceList = sources.map((source) =>
    `- **${source.sourceId} — ${escapeMarkdownLabel(source.name)}**: [Open in Google Drive](${sourceUrl(source)})`);
  const evidenceList = [...evidenceReferences.values()].map(({ id, source, quote }) =>
    `[^${id}]: **${source.sourceId} — ${escapeMarkdownLabel(source.name)}** — <code>${escapeHtml(JSON.stringify(quote))}</code> — [Open source](${sourceUrl(source)})`);

  return [
    `# ${escapeMarkdownHeading(result.title)}`,
    "",
    mode === "FULL"
      ? "> AI-generated detail-preserving merge. Review the cited evidence and merge notes; conflicting claims are flagged, not fact-checked externally."
      : "> AI-generated summary consolidation. Review the cited evidence and merge notes before sharing.",
    "",
    ...sections,
    ...notes,
    "## Sources",
    "",
    ...sourceList,
    "",
    "## Exact source evidence",
    "",
    ...evidenceList,
    "",
  ].join("\n");
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

export function safeDraftFilename(value: string): string {
  const filename = value
    .trim()
    .replace(/[<>:"/\\|?*\u0000-\u001f]/g, "-")
    .replace(/\s+/g, " ")
    .replace(/[. ]+$/g, "")
    .slice(0, 100);
  return filename || "driveatlas-draft";
}
