package com.driveatlas.api.resources;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class ResourceController {
    private final ResourceService service;

    public ResourceController(ResourceService service) {
        this.service = service;
    }

    @GetMapping("/session")
    SessionResponse session(@AuthenticationPrincipal OidcUser user) {
        return new SessionResponse(user.getSubject(), user.getEmail(), user.getFullName());
    }

    @GetMapping("/resources")
    List<ResourceRepository.SelectedResource> list(@AuthenticationPrincipal OidcUser user) {
        return service.list(user);
    }

    @PostMapping("/resources")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void select(@AuthenticationPrincipal OidcUser user, @Valid @RequestBody ResourceSelection selection) {
        service.save(user, selection);
    }

    @PostMapping("/resources/{providerId}/scan")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void saveFolderScan(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String providerId,
            @Valid @RequestBody ResourceScanRequest request) {
        if (!service.saveFolderScan(user, providerId, request.resources())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Selected folder not found");
        }
    }

    @DeleteMapping("/resources/{providerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal OidcUser user, @PathVariable String providerId) {
        if (!service.delete(user, providerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Selected resource not found");
        }
    }

    @PostMapping("/resources/{providerId}/tags")
    ResourceRepository.ResourceTag addTag(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String providerId,
            @Valid @RequestBody ResourceTagRequest request) {
        return service.addTag(user, providerId, request.name())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Selected resource not found"));
    }

    @DeleteMapping("/resources/{providerId}/tags/{tagId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeTag(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String providerId,
            @PathVariable UUID tagId) {
        if (!service.removeTag(user, providerId, tagId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource tag not found");
        }
    }

    @DeleteMapping("/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAccount(@AuthenticationPrincipal OidcUser user) {
        service.deleteAccount(user);
    }

    record SessionResponse(String subject, String email, String name) {
    }
}
