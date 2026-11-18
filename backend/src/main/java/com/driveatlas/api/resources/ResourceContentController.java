package com.driveatlas.api.resources;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/resources/{providerId}")
public class ResourceContentController {
    private final ResourceContentService service;

    public ResourceContentController(ResourceContentService service) {
        this.service = service;
    }

    @GetMapping("/content")
    ResourceContentResponse getContent(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String providerId) {
        ResourceRepository.StoredContent content = service.get(user, providerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No saved text for this file"));
        return new ResourceContentResponse(content.text(), content.extractedAt());
    }

    @PutMapping("/content")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void saveContent(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String providerId,
            @Valid @RequestBody ResourceContentRequest request) {
        service.save(user, providerId, request.text());
    }

    @DeleteMapping("/content")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteContent(@AuthenticationPrincipal OidcUser user, @PathVariable String providerId) {
        service.delete(user, providerId);
    }

    @PostMapping("/tag-suggestions")
    List<TagSuggestion> suggestTags(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String providerId,
            @Valid @RequestBody TagSuggestionRequest request) {
        return service.suggestTags(user, providerId, request.text());
    }

    record ResourceContentResponse(String text, java.time.Instant extractedAt) {
    }
}
