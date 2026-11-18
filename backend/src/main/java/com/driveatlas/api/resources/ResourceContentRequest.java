package com.driveatlas.api.resources;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResourceContentRequest(
        @NotNull @Size(max = 500_000) String text) {
}
