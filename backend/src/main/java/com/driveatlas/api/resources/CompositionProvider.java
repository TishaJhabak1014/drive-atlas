package com.driveatlas.api.resources;

import java.util.List;

public interface CompositionProvider {
    CompositionResult compose(List<CompositionSource> sources, CompositionMode mode);
}
