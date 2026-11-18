package com.driveatlas.api.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GeminiTagSuggestionProviderTests {
    @Test
    void categorizesCommonGeminiApiFailuresWithoutExposingProviderDetails() {
        assertEquals(
                "credentials or API permission issue",
                GeminiTagSuggestionProvider.failureCategory(
                        new IllegalStateException("403 PERMISSION_DENIED: sensitive response details")));
        assertEquals(
                "configured model not found",
                GeminiTagSuggestionProvider.failureCategory(
                        new IllegalStateException("404 NOT_FOUND: model unavailable")));
        assertEquals(
                "quota or rate limit",
                GeminiTagSuggestionProvider.failureCategory(
                        new IllegalStateException("429 RESOURCE_EXHAUSTED: quota exceeded")));
        assertEquals(
                "provider request failed",
                GeminiTagSuggestionProvider.failureCategory(
                        new IllegalStateException("unexpected failure with sensitive details")));
    }
}
