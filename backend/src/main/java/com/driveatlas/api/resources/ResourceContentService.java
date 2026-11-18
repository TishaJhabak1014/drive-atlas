package com.driveatlas.api.resources;

import com.driveatlas.api.account.AccountRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ResourceContentService {
    private final AccountRepository accounts;
    private final ResourceRepository resources;
    private final TagSuggestionProvider tagSuggestionProvider;

    public ResourceContentService(
            AccountRepository accounts,
            ResourceRepository resources,
            TagSuggestionProvider tagSuggestionProvider) {
        this.accounts = accounts;
        this.resources = resources;
        this.tagSuggestionProvider = tagSuggestionProvider;
    }

    public Optional<ResourceRepository.StoredContent> get(OidcUser user, String providerId) {
        UUID accountId = accountId(user);
        requireSelectedScopeFile(accountId, providerId);
        return resources.findContent(accountId, providerId);
    }

    @Transactional
    public void save(OidcUser user, String providerId, String text) {
        UUID accountId = accountId(user);
        requireSelectedScopeFile(accountId, providerId);
        if (!resources.saveContent(accountId, providerId, text)) {
            throw notFound();
        }
    }

    @Transactional
    public void delete(OidcUser user, String providerId) {
        UUID accountId = accountId(user);
        requireSelectedScopeFile(accountId, providerId);
        resources.deleteContent(accountId, providerId);
    }

    public List<TagSuggestion> suggestTags(OidcUser user, String providerId, String text) {
        UUID accountId = accountId(user);
        ResourceRepository.ResourceDescriptor resource = resources.findFileInSelectedScope(accountId, providerId)
                .orElseThrow(ResourceContentService::notFound);
        return tagSuggestionProvider.suggest(resource.name(), resource.mimeType(), text);
    }

    private void requireSelectedScopeFile(UUID accountId, String providerId) {
        if (resources.findFileInSelectedScope(accountId, providerId).isEmpty()) {
            throw notFound();
        }
    }

    private UUID accountId(OidcUser user) {
        return accounts.upsert(user.getSubject(), user.getEmail(), user.getFullName());
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found in a selected Drive scope");
    }
}
