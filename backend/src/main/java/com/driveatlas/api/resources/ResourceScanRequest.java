package com.driveatlas.api.resources;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ResourceScanRequest(
        @NotEmpty @Size(max = 5001) List<@Valid ResourceSelection> resources) {
}
