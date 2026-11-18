package com.driveatlas.api.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class TagSuggestionParserTests {
    private final TagSuggestionParser parser = new TagSuggestionParser(
            new ObjectMapper(), new SourceEvidenceMatcher());

    @Test
    void acceptsOnlySuggestionsGroundedInTheSubmittedText() {
        List<TagSuggestion> suggestions = parser.parse(
                """
                {"suggestions":[
                  {"name":"Caching","confidence":0.92,"evidence":"cache responses for ten minutes"}
                ]}
                """,
                "The service will cache responses for ten minutes.");

        assertEquals(List.of(new TagSuggestion(
                "Caching", 0.92, "cache responses for ten minutes")), suggestions);
    }

    @Test
    void rejectsEvidenceNotPresentInTheSubmittedText() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> parser.parse(
                        """
                        {"suggestions":[
                          {"name":"Caching","confidence":0.92,"evidence":"cache all private data forever"}
                        ]}
                        """,
                        "The service will cache responses for ten minutes."));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        assertEquals(
                "Gemini returned invalid tag suggestions: supporting evidence was not an exact quote from the submitted text",
                exception.getReason());
    }

    @Test
    void acceptsQuotedEvidenceAfterRemovingOnlyItsOuterQuoteMarks() {
        List<TagSuggestion> suggestions = parser.parse(
                """
                {"suggestions":[
                  {"name":"Caching","confidence":0.92,"evidence":"\\"cache responses for ten minutes\\""}
                ]}
                """,
                "The service will cache responses for ten minutes.");

        assertEquals("cache responses for ten minutes", suggestions.get(0).evidence());
    }

    @Test
    void matchesLineWrappedEvidenceAndReturnsTheExactSourceSubstring() {
        String sourceText = "Cache responses\r\n    for ten minutes.";
        List<TagSuggestion> suggestions = parser.parse(
                """
                {"suggestions":[
                  {"name":"Caching","confidence":0.92,"evidence":"Cache responses for ten minutes."}
                ]}
                """,
                sourceText);

        assertEquals(sourceText, suggestions.get(0).evidence());
    }

    @Test
    void rejectsMalformedProviderJson() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> parser.parse("{not json}", "some source text"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
    }
}
