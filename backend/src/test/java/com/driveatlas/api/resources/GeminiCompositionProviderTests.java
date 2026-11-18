package com.driveatlas.api.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GeminiCompositionProviderTests {
    private static final List<CompositionSource> SOURCES = List.of(
            new CompositionSource("S1", "Short TTL", "text/plain",
                    "Use a five-minute TTL for cached responses."),
            new CompositionSource("S2", "Long TTL", "text/plain",
                    "Use a ten-minute TTL for cached responses."));
    private static final String VALID_RESPONSE = """
            {"title":"Caching","sections":[{"heading":"TTL","paragraphs":[
              {"text":"The sources specify different TTLs.","evidence":[
                {"evidenceId":"E1"},
                {"evidenceId":"E2"}
              ]}
            ]}],"notes":[]}
            """;

    @Test
    void retriesOnceWhenCitationUsesAnUnknownExcerptId() {
        ChatModel model = mock(ChatModel.class);
        GeminiChatModelFactory modelFactory = mock(GeminiChatModelFactory.class);
        when(modelFactory.create(32_768)).thenReturn(Optional.of(model));
        when(model.chat(anyString())).thenReturn(
                """
                {"title":"Caching","sections":[{"heading":"TTL","paragraphs":[
                  {"text":"Cache responses with an appropriate TTL.","evidence":[
                    {"evidenceId":"E999"},
                    {"evidenceId":"E2"}
                  ]}
                ]}],"notes":[]}
                """,
                """
                {"title":"Caching","sections":[{"heading":"TTL","paragraphs":[
                  {"text":"Cache responses with an appropriate TTL.","evidence":[
                    {"evidenceId":"E1"},
                    {"evidenceId":"E2"}
                  ]}
                ]}],"notes":[]}
                """);

        GeminiCompositionProvider provider = new GeminiCompositionProvider(
                modelFactory,
                new CompositionResponseParser(new ObjectMapper(), new SourceEvidenceMatcher()),
                new CompositionEvidenceCatalog(),
                new ObjectMapper());
        CompositionResult result = provider.compose(List.of(
                new CompositionSource("S1", "Short TTL", "text/plain",
                        "Use a five-minute TTL for cached responses."),
                new CompositionSource("S2", "Long TTL", "text/plain",
                        "Use a ten-minute TTL for cached responses.")), CompositionMode.SUMMARIZE);

        assertEquals("Caching", result.title());
        assertEquals("Use a five-minute TTL for cached responses.",
                result.sections().get(0).paragraphs().get(0).evidence().get(0).quote());
        verify(model, times(2)).chat(anyString());
    }

    @Test
    void fullModeUsesDetailPreservingInstructionsAndSourceConflictPolicy() {
        ChatModel model = mock(ChatModel.class);
        GeminiChatModelFactory modelFactory = mock(GeminiChatModelFactory.class);
        when(modelFactory.create(32_768)).thenReturn(Optional.of(model));
        when(model.chat(anyString())).thenReturn(
                """
                {"title":"Caching","sections":[{"heading":"TTL","paragraphs":[
                  {"text":"Cache responses using each source's stated TTL.","evidence":[
                    {"evidenceId":"E1"},
                    {"evidenceId":"E2"}
                  ]}
                ]}],"notes":[{"category":"conflict","summary":"The sources specify different TTL values.",
                  "evidence":[
                    {"evidenceId":"E1"},
                    {"evidenceId":"E2"}
                  ]}]}
                """);

        GeminiCompositionProvider provider = new GeminiCompositionProvider(
                modelFactory,
                new CompositionResponseParser(new ObjectMapper(), new SourceEvidenceMatcher()),
                new CompositionEvidenceCatalog(),
                new ObjectMapper());
        provider.compose(List.of(
                new CompositionSource("S1", "Short TTL", "text/plain",
                        "Use a five-minute TTL for cached responses."),
                new CompositionSource("S2", "Long TTL", "text/plain",
                        "Use a ten-minute TTL for cached responses.")), CompositionMode.FULL);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(model).chat(prompt.capture());
        assertTrue(prompt.getValue().contains("detail-preserving merge, not a summary"));
        assertTrue(prompt.getValue().contains("discard either claim"));
        assertTrue(prompt.getValue().contains("\"validEvidenceIds\""));
    }

    @Test
    void retriesMalformedJsonAndMissingEvidenceOnce() {
        List<String> invalidResponses = List.of(
                "{not json",
                """
                {"title":"Caching","sections":[{"heading":"TTL","paragraphs":[
                  {"text":"The sources specify different TTLs.","evidence":[]}
                ]}],"notes":[]}
                """);

        for (String invalidResponse : invalidResponses) {
            ChatModel model = mock(ChatModel.class);
            GeminiChatModelFactory modelFactory = mock(GeminiChatModelFactory.class);
            when(modelFactory.create(32_768)).thenReturn(Optional.of(model));
            when(model.chat(anyString())).thenReturn(invalidResponse, VALID_RESPONSE);
            GeminiCompositionProvider provider = new GeminiCompositionProvider(
                    modelFactory,
                    new CompositionResponseParser(new ObjectMapper(), new SourceEvidenceMatcher()),
                    new CompositionEvidenceCatalog(),
                    new ObjectMapper());

            CompositionResult result = provider.compose(SOURCES, CompositionMode.SUMMARIZE);

            assertEquals("Caching", result.title());
            ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
            verify(model, times(2)).chat(prompts.capture());
            assertTrue(prompts.getAllValues().get(1).contains("\"notes\": ["));
            assertTrue(prompts.getAllValues().get(1).contains("Every source must be represented"));
        }
    }
}
