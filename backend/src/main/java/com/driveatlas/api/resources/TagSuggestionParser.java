package com.driveatlas.api.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TagSuggestionParser {
    private static final int MAX_SUGGESTIONS = 5;
    private static final int MAX_MODEL_EVIDENCE_LENGTH = 240;
    private static final int MAX_SOURCE_EVIDENCE_LENGTH = 500;
    private final ObjectMapper objectMapper;
    private final SourceEvidenceMatcher evidenceMatcher;

    public TagSuggestionParser(ObjectMapper objectMapper, SourceEvidenceMatcher evidenceMatcher) {
        this.objectMapper = objectMapper;
        this.evidenceMatcher = evidenceMatcher;
    }

    public List<TagSuggestion> parse(String json, String sourceText) {
        JsonNode suggestions;
        try {
            suggestions = objectMapper.readTree(json).path("suggestions");
        } catch (JsonProcessingException exception) {
            throw invalidModelResponse("the response was not valid JSON");
        }
        if (!suggestions.isArray()) {
            throw invalidModelResponse("the response did not contain a suggestions array");
        }

        List<TagSuggestion> result = new ArrayList<>();
        Set<String> normalizedNames = new HashSet<>();
        for (JsonNode suggestion : suggestions) {
            String name = suggestion.path("name").asText("").trim();
            String evidence = stripSurroundingQuotes(suggestion.path("evidence").asText("").trim());
            String normalizedEvidence = evidenceMatcher.normalizeWhitespace(evidence);
            JsonNode confidenceNode = suggestion.path("confidence");
            if (name.isEmpty() || name.length() > 80) {
                throw invalidModelResponse("a suggested tag name was empty or too long");
            }
            if (normalizedEvidence.length() < 8 || normalizedEvidence.length() > MAX_MODEL_EVIDENCE_LENGTH) {
                throw invalidModelResponse("supporting evidence must be between 8 and 240 characters");
            }
            String exactEvidence = evidenceMatcher.findExactQuote(sourceText, normalizedEvidence);
            if (exactEvidence == null) {
                throw invalidModelResponse("supporting evidence was not an exact quote from the submitted text");
            }
            if (exactEvidence.length() > MAX_SOURCE_EVIDENCE_LENGTH) {
                throw invalidModelResponse("supporting evidence exceeds the 500-character display limit");
            }
            if (!confidenceNode.isNumber()
                    || !Double.isFinite(confidenceNode.doubleValue())
                    || confidenceNode.doubleValue() < 0
                    || confidenceNode.doubleValue() > 1) {
                throw invalidModelResponse("a suggested confidence value was invalid");
            }
            if (normalizedNames.add(name.toLowerCase(Locale.ROOT))) {
                result.add(new TagSuggestion(name, confidenceNode.doubleValue(), exactEvidence));
                if (result.size() == MAX_SUGGESTIONS) break;
            }
        }
        return List.copyOf(result);
    }

    private String stripSurroundingQuotes(String evidence) {
        if (evidence.length() >= 2) {
            char first = evidence.charAt(0);
            char last = evidence.charAt(evidence.length() - 1);
            if ((first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '\u201c' && last == '\u201d')
                    || (first == '\u2018' && last == '\u2019')) {
                return evidence.substring(1, evidence.length() - 1).trim();
            }
        }
        return evidence;
    }

    private ResponseStatusException invalidModelResponse(String reason) {
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "Gemini returned invalid tag suggestions: " + reason);
    }
}
