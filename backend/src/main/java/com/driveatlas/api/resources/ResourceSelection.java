package com.driveatlas.api.resources;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record ResourceSelection(
        @NotBlank @Size(max = 512) String providerId,
        @NotBlank @Size(max = 1000) String name,
        @NotBlank @Size(max = 255) String mimeType,
        @NotNull ResourceType resourceType,
        Instant modifiedTime,
        @Size(max = 2048)
        @Pattern(regexp = "^https://(drive|docs)\\.google\\.com/.*$")
        String webViewLink,
        @Size(max = 512) String parentProviderId) {
    public enum ResourceType {
        FILE, FOLDER
    }
}
