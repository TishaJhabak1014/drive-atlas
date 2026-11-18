package com.driveatlas.api.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CompositionResponseParserTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CompositionResponseParser parser = new CompositionResponseParser(
            objectMapper, new SourceEvidenceMatcher());
    private final List<CompositionSource> sources = List.of(
            new CompositionSource("S1", "Strategies", "text/plain",
                    "Cache repeated responses. Keep invalidation rules beside the cache."),
            new CompositionSource("S2", "Invalidation", "text/markdown",
                    "Keep invalidation rules beside the cache.\nA short TTL limits stale data."));

    @Test
    void acceptsCitedParagraphsAndMergeNotesWithExactSourceEvidence() {
        CompositionResult result = parser.parse(
                """
                {
                  "title":"Caching",
                  "sections":[
                    {"heading":"Strategies","paragraphs":[
                      {"text":"Cache repeated responses.","evidence":[
                        {"evidenceId":"E1"}
                      ]}
                    ]},
                    {"heading":"Invalidation","paragraphs":[
                      {"text":"Keep invalidation rules beside the cache.","evidence":[
                        {"evidenceId":"E2"},
                        {"evidenceId":"E3"}
                      ]}
                    ]}
                  ],
                  "notes":[
                    {"category":"duplicate","summary":"Combined repeated invalidation guidance.",
                      "evidence":[
                        {"evidenceId":"E2"},
                        {"evidenceId":"E3"}
                      ]}
                  ]
                }
                """,
                sources);

        assertEquals("Caching", result.title());
        assertEquals(2, result.sections().size());
        assertEquals("Keep invalidation rules beside the cache.",
                result.sections().get(1).paragraphs().get(0).text());
        assertEquals("duplicate", result.notes().get(0).category());
    }

    @Test
    void rejectsCitationsToUnknownEvidenceIds() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> parser.parse(
                        """
                        {"title":"Caching","sections":[{"heading":"Topic","paragraphs":[
                          {"text":"The cache is always consistent.","evidence":[
                            {"evidenceId":"E999"}
                          ]}
                        ]}],"notes":[]}
                        """,
                        sources));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        assertEquals(
                "Gemini returned an invalid consolidation: a citation referenced an unknown evidence ID",
                exception.getReason());
    }

    @Test
    void requiresEverySourceToBeRepresented() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> parser.parse(
                        """
                        {"title":"Caching","sections":[{"heading":"Topic","paragraphs":[
                          {"text":"Cache repeated responses.","evidence":[
                            {"evidenceId":"E1"}
                          ]}
                        ]}],"notes":[]}
                        """,
                        sources));

        assertEquals(
                "Gemini returned an invalid consolidation: not every source was represented in cited content or merge notes",
                exception.getReason());
    }

    @Test
    void rejectsUnknownExcerptIds() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> parser.parse(
                        """
                        {"title":"Caching","sections":[{"heading":"Topic","paragraphs":[
                          {"text":"Cache repeated responses.","evidence":[
                            {"evidenceId":"E999"}
                          ]}
                        ]}],"notes":[]}
                        """,
                        sources));

        assertEquals(
                "Gemini returned an invalid consolidation: a citation referenced an unknown evidence ID",
                exception.getReason());
    }

    @Test
    void acceptsLegacyExactQuoteCitationsAndReturnsVerifiedSourceText() {
        CompositionResult result = parser.parse(
                """
                {"title":"Caching","sections":[{"heading":"Topic","paragraphs":[
                  {"text":"The two files discuss caching.","evidence":[
                    {"evidenceId":"NOT-A-REAL-ID","quote":"Cache repeated responses."},
                    {"sourceId":"S2","quote":"A short TTL limits stale data."}
                  ]}
                ]}],"notes":[]}
                """,
                sources);

        assertEquals("Cache repeated responses.",
                result.sections().get(0).paragraphs().get(0).evidence().get(0).quote());
        assertEquals("A short TTL limits stale data.",
                result.sections().get(0).paragraphs().get(0).evidence().get(1).quote());
    }

    @Test
    void acceptsEvidenceIdsReturnedAsStrings() {
        CompositionResult result = parser.parse(
                """
                {"title":"Caching","sections":[{"heading":"Topic","paragraphs":[
                  {"text":"The two files discuss caching.","evidence":["E1","E4"]}
                ]}],"notes":[]}
                """,
                sources);

        assertEquals(2, result.sections().get(0).paragraphs().get(0).evidence().size());
    }

    @Test
    void acceptsMoreThanTwentySectionsForDetailPreservingMerges() throws Exception {
        var root = objectMapper.createObjectNode();
        root.put("title", "Caching reference");
        var sections = root.putArray("sections");
        for (int index = 0; index < 21; index += 1) {
            var section = sections.addObject();
            section.put("heading", "Topic " + index);
            var paragraph = section.putArray("paragraphs").addObject();
            paragraph.put("text", "Caching details are cited from the selected sources.");
            var evidence = paragraph.putArray("evidence");
            evidence.addObject().put("evidenceId", "E1");
            evidence.addObject().put("evidenceId", "E4");
        }
        root.putArray("notes");

        CompositionResult result = parser.parse(objectMapper.writeValueAsString(root), sources);

        assertEquals(21, result.sections().size());
    }

    @Test
    void explainsMissingNotesArrayForCorrectiveRetry() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> parser.parse(
                        """
                        {"title":"Caching","sections":[{"heading":"Topic","paragraphs":[
                          {"text":"Cache repeated responses.","evidence":[
                            {"evidenceId":"E1"}
                          ]}
                        ]}]}
                        """,
                        sources));

        assertEquals(
                "Gemini returned an invalid consolidation: notes must be an array, even when there are no notes",
                exception.getReason());
    }
}
