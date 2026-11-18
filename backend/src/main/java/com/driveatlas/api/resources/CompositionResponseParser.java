package com.driveatlas.api.resources;

import com.driveatlas.api.resources.CompositionResult.Evidence;
import com.driveatlas.api.resources.CompositionResult.MergeNote;
import com.driveatlas.api.resources.CompositionResult.Paragraph;
import com.driveatlas.api.resources.CompositionResult.Section;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CompositionResponseParser {
    private static final int MAX_SECTIONS = 100;
    private static final int MAX_PARAGRAPHS = 300;
    private static final int MAX_NOTES = 100;
    private static final int MAX_EVIDENCE_PER_ITEM = 5;
    private static final int MAX_FALLBACK_QUOTE_LENGTH = 500;
    private static final Set<String> NOTE_CATEGORIES = Set.of(
            "duplicate", "unique", "structure", "correction", "conflict");

    private final ObjectMapper objectMapper;
    private final SourceEvidenceMatcher evidenceMatcher;

    public CompositionResponseParser(ObjectMapper objectMapper, SourceEvidenceMatcher evidenceMatcher) {
        this.objectMapper = objectMapper;
        this.evidenceMatcher = evidenceMatcher;
    }

    public CompositionResult parse(String json, List<CompositionSource> sources) {
        CompositionEvidenceCatalog.Catalog catalog = new CompositionEvidenceCatalog().create(sources);
        return parse(json, sources, catalog.excerptsById());
    }

    public CompositionResult parse(
            String json,
            List<CompositionSource> sources,
            Map<String, CompositionEvidenceCatalog.Excerpt> excerptsById) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw invalidResponse("the response was not valid JSON");
        }

        String title = boundedText(root.path("title").asText(""), 1, 120);
        JsonNode sectionNodes = root.path("sections");
        JsonNode noteNodes = root.path("notes");
        if (title == null) throw invalidResponse("title must be a non-empty string of at most 120 characters");
        if (!sectionNodes.isArray()) throw invalidResponse("sections must be an array");
        if (sectionNodes.isEmpty()) throw invalidResponse("sections must contain at least one section");
        if (sectionNodes.size() > MAX_SECTIONS) {
            throw invalidResponse("the response contained more than 100 sections");
        }
        if (!noteNodes.isArray()) throw invalidResponse("notes must be an array, even when there are no notes");
        if (noteNodes.size() > MAX_NOTES) throw invalidResponse("the response contained more than 100 merge notes");

        Map<String, CompositionSource> sourcesById = new HashMap<>();
        for (CompositionSource source : sources) sourcesById.put(source.sourceId(), source);
        Set<String> representedSourceIds = new HashSet<>();
        List<Section> sections = new ArrayList<>();
        int paragraphCount = 0;
        for (JsonNode sectionNode : sectionNodes) {
            String heading = boundedText(sectionNode.path("heading").asText(""), 1, 120);
            JsonNode paragraphNodes = sectionNode.path("paragraphs");
            if (heading == null || !paragraphNodes.isArray() || paragraphNodes.isEmpty()) {
                throw invalidResponse("a section heading or paragraph list was invalid");
            }

            List<Paragraph> paragraphs = new ArrayList<>();
            for (JsonNode paragraphNode : paragraphNodes) {
                paragraphCount += 1;
                if (paragraphCount > MAX_PARAGRAPHS) {
                    throw invalidResponse("the response contained more than 300 paragraphs");
                }
                String text = boundedText(paragraphNode.path("text").asText(""), 1, 5000);
                if (text == null) throw invalidResponse("a paragraph was empty or too long");
                List<Evidence> evidence = parseEvidence(
                        paragraphNode.path("evidence"), sourcesById, excerptsById, representedSourceIds);
                paragraphs.add(new Paragraph(text, evidence));
            }
            sections.add(new Section(heading, List.copyOf(paragraphs)));
        }

        List<MergeNote> notes = new ArrayList<>();
        for (JsonNode noteNode : noteNodes) {
            String category = noteNode.path("category").asText("");
            String summary = boundedText(noteNode.path("summary").asText(""), 1, 500);
            if (!NOTE_CATEGORIES.contains(category) || summary == null) {
                throw invalidResponse("a merge note had an invalid category or summary");
            }
            List<Evidence> evidence = parseEvidence(
                    noteNode.path("evidence"), sourcesById, excerptsById, representedSourceIds);
            notes.add(new MergeNote(category, summary, evidence));
        }

        Set<String> allSourceIds = sources.stream()
                .map(CompositionSource::sourceId)
                .collect(java.util.stream.Collectors.toSet());
        if (!representedSourceIds.containsAll(allSourceIds)) {
            throw invalidResponse("not every source was represented in cited content or merge notes");
        }
        return new CompositionResult(title, List.copyOf(sections), List.copyOf(notes));
    }

    private List<Evidence> parseEvidence(
            JsonNode evidenceNodes,
            Map<String, CompositionSource> sourcesById,
            Map<String, CompositionEvidenceCatalog.Excerpt> excerptsById,
            Set<String> representedSourceIds) {
        if (!evidenceNodes.isArray() || evidenceNodes.isEmpty()
                || evidenceNodes.size() > MAX_EVIDENCE_PER_ITEM) {
            throw invalidResponse("each paragraph and merge note must have one to five evidence IDs");
        }

        List<Evidence> evidence = new ArrayList<>();
        for (JsonNode evidenceNode : evidenceNodes) {
            String evidenceId = evidenceNode.isTextual()
                    ? evidenceNode.asText().trim()
                    : firstText(evidenceNode, "evidenceId", "excerptId", "id");
            CompositionEvidenceCatalog.Excerpt excerpt = excerptsById.get(evidenceId);
            if (excerpt == null && !evidenceId.isEmpty()) {
                excerpt = excerptsById.get(evidenceId.toUpperCase(java.util.Locale.ROOT));
            }
            if (excerpt != null) {
                addVerifiedExcerpt(excerpt, sourcesById, evidence, representedSourceIds);
                continue;
            }

            Evidence exactQuote = exactQuoteFallback(evidenceNode, sourcesById);
            if (exactQuote == null) throw invalidResponse("a citation referenced an unknown evidence ID");
            evidence.add(exactQuote);
            representedSourceIds.add(exactQuote.sourceId());
        }
        return List.copyOf(evidence);
    }

    private void addVerifiedExcerpt(
            CompositionEvidenceCatalog.Excerpt excerpt,
            Map<String, CompositionSource> sourcesById,
            List<Evidence> evidence,
            Set<String> representedSourceIds) {
        CompositionSource source = sourcesById.get(excerpt.sourceId());
        if (source == null) throw invalidResponse("a citation referenced an unknown source");
        String exactQuote = evidenceMatcher.findExactQuote(source.text(), excerpt.quote());
        if (exactQuote == null) throw invalidResponse("a source excerpt could not be verified");
        evidence.add(new Evidence(excerpt.sourceId(), exactQuote));
        representedSourceIds.add(excerpt.sourceId());
    }

    private Evidence exactQuoteFallback(
            JsonNode evidenceNode,
            Map<String, CompositionSource> sourcesById) {
        String sourceId = evidenceNode.path("sourceId").asText("");
        String quote = evidenceNode.path("quote").asText("").trim();
        if (quote.length() < 8 || quote.length() > MAX_FALLBACK_QUOTE_LENGTH) return null;

        CompositionSource source = sourcesById.get(sourceId);
        if (source != null) {
            String exactQuote = evidenceMatcher.findExactQuote(source.text(), quote);
            if (exactQuote != null) return new Evidence(sourceId, exactQuote);
        }
        for (CompositionSource candidate : sourcesById.values()) {
            String exactQuote = evidenceMatcher.findExactQuote(candidate.text(), quote);
            if (exactQuote != null) return new Evidence(candidate.sourceId(), exactQuote);
        }
        return null;
    }

    private static String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode field = node.path(fieldName);
            if (field.isTextual()) return field.asText().trim();
        }
        return "";
    }

    private static String boundedText(String value, int minimum, int maximum) {
        String text = value.trim();
        return text.length() >= minimum && text.length() <= maximum ? text : null;
    }

    private static InvalidCompositionResponseException invalidResponse(String reason) {
        return new InvalidCompositionResponseException(reason);
    }

    static class InvalidCompositionResponseException extends ResponseStatusException {
        InvalidCompositionResponseException(String reason) {
            super(HttpStatus.BAD_GATEWAY, "Gemini returned an invalid consolidation: " + reason);
        }
    }

}
