package com.driveatlas.api.resources;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResourceTagRequest(
        @NotBlank @Size(max = 80) String name) {
}
