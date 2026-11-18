package com.driveatlas.api.resources;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CompositionRequest(
        CompositionMode mode,
        @NotEmpty @Size(min = 2, max = 10) List<@Valid CompositionSourceRequest> sources) {
    public CompositionRequest(List<@Valid CompositionSourceRequest> sources) {
        this(CompositionMode.SUMMARIZE, sources);
    }

    public CompositionRequest {
        if (mode == null) mode = CompositionMode.SUMMARIZE;
    }
}
