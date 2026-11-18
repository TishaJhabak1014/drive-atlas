package com.driveatlas.api.resources;

import java.util.List;

public interface TagSuggestionProvider {
    List<TagSuggestion> suggest(String resourceName, String mimeType, String text);
}
