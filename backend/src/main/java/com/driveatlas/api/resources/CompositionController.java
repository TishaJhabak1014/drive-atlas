package com.driveatlas.api.resources;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/compositions")
public class CompositionController {
    private final CompositionService service;

    public CompositionController(CompositionService service) {
        this.service = service;
    }

    @PostMapping
    CompositionResult compose(
            @AuthenticationPrincipal OidcUser user,
            @Valid @RequestBody CompositionRequest request) {
        return service.compose(user, request);
    }
}
