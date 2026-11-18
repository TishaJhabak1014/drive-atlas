import assert from "node:assert/strict";
import { test } from "node:test";
import {
  renderConsolidationMarkdown,
  MAX_COMPOSER_SOURCES,
  MAX_COMPOSER_TEXT_LENGTH,
  safeDraftFilename,
  validateDraftSources,
} from "../src/draftComposer.ts";

test("consolidated Markdown cites generated content and exact source evidence", () => {
  const sources = [
    {
      sourceId: "S1",
      providerId: "source (one)",
      name: "Notes [one] (final).md",
      text: "First source text.",
    },
    {
      sourceId: "S2",
      providerId: "source (two)",
      name: "Second\n- [Injected](https://example.com)",
      text: "Second source text.",
    },
  ];
  const draft = renderConsolidationMarkdown({
    title: "Caching",
    sections: [{
      heading: "Expiration",
      paragraphs: [{
        text: "Use a short TTL to limit stale cache entries.",
        evidence: [
          { sourceId: "S1", quote: "First source text." },
          { sourceId: "S2", quote: "Second source text." },
        ],
      }],
    }],
    notes: [{
      category: "duplicate",
      summary: "Combined repeated TTL guidance.",
      evidence: [
        { sourceId: "S1", quote: "First source text." },
        { sourceId: "S2", quote: "Second source text." },
      ],
    }],
  }, sources);

  assert.match(draft, /^# Caching/m);
  assert.match(draft, /Use a short TTL to limit stale cache entries/);
  assert.ok(draft.includes(String.raw`Notes \[one\] \(final\).md`));
  assert.match(draft, /https:\/\/drive\.google\.com\/open\?id=source%20%28one%29/);
  assert.match(draft, /https:\/\/drive\.google\.com\/open\?id=source%20%28two%29/);
  assert.doesNotMatch(draft, /\n- \[Injected\]/);
  assert.match(draft, /Exact source evidence/);
  assert.match(draft, /First source text/);
  assert.match(draft, /Second source text/);
  assert.doesNotMatch(draft, /### Source 1:/);

  const fullMerge = renderConsolidationMarkdown({
    title: "Caching",
    sections: [],
    notes: [],
  }, sources, "FULL");
  assert.match(fullMerge, /detail-preserving merge/);
  assert.match(fullMerge, /conflicting claims are flagged, not fact-checked externally/);
});

test("download filenames are safe and never empty", () => {
  assert.equal(safeDraftFilename("Notes: /draft?.md"), "Notes- -draft-.md");
  assert.equal(safeDraftFilename("...   "), "driveatlas-draft");
});

test("composer accepts exact source and text limits and rejects values beyond them", () => {
  const source = (providerId, text) => ({ sourceId: providerId, providerId, name: providerId, text });
  const atSourceLimit = Array.from({ length: MAX_COMPOSER_SOURCES }, (_, index) =>
    source(`source-${index}`, "x"));
  const atTextLimit = [
    source("first", "x".repeat(MAX_COMPOSER_TEXT_LENGTH / 2)),
    source("second", "y".repeat(MAX_COMPOSER_TEXT_LENGTH / 2)),
  ];

  assert.equal(validateDraftSources([source("one", "x")]), "Select at least two supported files to compose a draft.");
  assert.equal(validateDraftSources(atSourceLimit), undefined);
  assert.match(validateDraftSources([...atSourceLimit, source("extra", "x")]), /at most 10 source files/);
  assert.equal(validateDraftSources(atTextLimit), undefined);
  assert.match(validateDraftSources([...atTextLimit, source("extra", "x")]), /60,000 character draft limit/);
  assert.match(validateDraftSources([source("one", ""), source("two", "  ")]), /contain no extracted text/);
});
