package com.driveatlas.api.resources;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TagSuggestionRequest(
        @NotBlank @Size(max = 30_000) String text) {
}
