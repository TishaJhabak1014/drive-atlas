package com.driveatlas.api.resources;

import com.driveatlas.api.account.AccountRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CompositionService {
    public static final int MAX_INPUT_CHARACTERS = 60_000;

    private static final String GOOGLE_DOC_MIME_TYPE = "application/vnd.google-apps.document";
    private static final Set<String> TEXT_MIME_TYPES = Set.of("text/plain", "text/markdown");
    private static final Set<String> UNKNOWN_MIME_TYPES = Set.of("application/octet-stream", "application/x-unknown");

    private final AccountRepository accounts;
    private final ResourceRepository resources;
    private final CompositionProvider compositionProvider;

    public CompositionService(
            AccountRepository accounts,
            ResourceRepository resources,
            CompositionProvider compositionProvider) {
        this.accounts = accounts;
        this.resources = resources;
        this.compositionProvider = compositionProvider;
    }

    public CompositionResult compose(OidcUser user, CompositionRequest request) {
        UUID accountId = accounts.upsert(user.getSubject(), user.getEmail(), user.getFullName());
        List<CompositionSource> sources = new ArrayList<>();
        Set<String> providerIds = new HashSet<>();
        int totalCharacters = 0;

        for (int index = 0; index < request.sources().size(); index += 1) {
            CompositionSourceRequest submitted = request.sources().get(index);
            if (!providerIds.add(submitted.providerId())) {
                throw badRequest("A source file can only be included once.");
            }

            ResourceRepository.ResourceDescriptor resource = resources
                    .findFileInSelectedScope(accountId, submitted.providerId())
                    .orElseThrow(CompositionService::notFound);
            if (!supportsTextComposition(resource)) {
                throw badRequest("Composition supports plain text, Markdown, and Google Docs only.");
            }

            totalCharacters += submitted.text().length();
            if (totalCharacters > MAX_INPUT_CHARACTERS) {
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "Combined source text exceeds the 60,000 character composition limit.");
            }
            sources.add(new CompositionSource(
                    "S" + (index + 1),
                    resource.name(),
                    resource.mimeType(),
                    submitted.text()));
        }

        return compositionProvider.compose(List.copyOf(sources), request.mode());
    }

    private boolean supportsTextComposition(ResourceRepository.ResourceDescriptor resource) {
        if (GOOGLE_DOC_MIME_TYPE.equals(resource.mimeType()) || TEXT_MIME_TYPES.contains(resource.mimeType())) {
            return true;
        }
        return UNKNOWN_MIME_TYPES.contains(resource.mimeType())
                && resource.name() != null
                && resource.name().matches("(?i).+\\.(md|txt)");
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found in a selected Drive scope");
    }
}
