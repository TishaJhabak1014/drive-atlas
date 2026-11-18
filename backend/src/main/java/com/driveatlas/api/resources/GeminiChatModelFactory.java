package com.driveatlas.api.resources;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GeminiChatModelFactory {
    private final String apiKey;
    private final String modelName;

    public GeminiChatModelFactory(
            @Value("${GEMINI_API_KEY:}") String apiKey,
            @Value("${GEMINI_MODEL:gemini-3.5-flash-lite}") String modelName) {
        this.apiKey = apiKey;
        this.modelName = modelName;
    }

    public Optional<ChatModel> create(int maxOutputTokens) {
        if (!StringUtils.hasText(apiKey)) return Optional.empty();
        return Optional.of(GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.1)
                .maxOutputTokens(maxOutputTokens)
                .timeout(Duration.ofSeconds(90))
                .responseFormat(ResponseFormat.JSON)
                .logRequestsAndResponses(false)
                .build());
    }

    public static String failureCategory(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            String details = message == null ? "" : message.toLowerCase(Locale.ROOT);
            String type = cause.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (details.contains("401") || details.contains("403")
                    || details.contains("api_key_invalid") || details.contains("permission_denied")) {
                return "credentials or API permission issue";
            }
            if (details.contains("404") || details.contains("not_found")) {
                return "configured model not found";
            }
            if (details.contains("429") || details.contains("resource_exhausted")
                    || details.contains("quota")) {
                return "quota or rate limit";
            }
            if (type.contains("timeout") || details.contains("timed out")) {
                return "provider timeout";
            }
            if (details.contains("5xx") || details.contains("503") || details.contains("unavailable")) {
                return "provider unavailable";
            }
        }
        return "provider request failed";
    }
}
