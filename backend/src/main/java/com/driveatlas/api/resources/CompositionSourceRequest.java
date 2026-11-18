package com.driveatlas.api.resources;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompositionSourceRequest(
        @NotBlank @Size(max = 512) String providerId,
        @NotBlank @Size(max = 60_000) String text) {
}
