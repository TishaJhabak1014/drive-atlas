package com.driveatlas.api.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.driveatlas.api.account.AccountRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.server.ResponseStatusException;

class ResourceContentServiceTests {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final ResourceRepository resources = mock(ResourceRepository.class);
    private final TagSuggestionProvider provider = mock(TagSuggestionProvider.class);
    private final ResourceContentService service = new ResourceContentService(accounts, resources, provider);
    private final OidcUser user = mock(OidcUser.class);
    private final UUID accountId = UUID.randomUUID();

    @Test
    void suggestionsAreRequestedForAFileInTheSelectedScope() {
        when(user.getSubject()).thenReturn("subject");
        when(user.getEmail()).thenReturn("user@example.com");
        when(user.getFullName()).thenReturn("User");
        when(accounts.upsert("subject", "user@example.com", "User")).thenReturn(accountId);
        when(resources.findFileInSelectedScope(accountId, "file-id"))
                .thenReturn(Optional.of(new ResourceRepository.ResourceDescriptor(
                        "file-id", "Notes", "text/plain")));
        List<TagSuggestion> suggestions = List.of(
                new TagSuggestion("research", 0.9, "supporting source excerpt"));
        when(provider.suggest("Notes", "text/plain", "source text")).thenReturn(suggestions);

        assertEquals(suggestions, service.suggestTags(user, "file-id", "source text"));
        verify(provider).suggest("Notes", "text/plain", "source text");
    }

    @Test
    void scannedDescendantCanUseTagSuggestionsWhenItIsInTheSelectedFolderScope() {
        when(user.getSubject()).thenReturn("subject");
        when(user.getEmail()).thenReturn("user@example.com");
        when(user.getFullName()).thenReturn("User");
        when(accounts.upsert("subject", "user@example.com", "User")).thenReturn(accountId);
        when(resources.findFileInSelectedScope(accountId, "descendant-id"))
                .thenReturn(Optional.of(new ResourceRepository.ResourceDescriptor(
                        "descendant-id", "Nested notes", "text/plain")));
        List<TagSuggestion> suggestions = List.of(
                new TagSuggestion("research", 0.9, "supporting source excerpt"));
        when(provider.suggest("Nested notes", "text/plain", "source text")).thenReturn(suggestions);

        assertEquals(suggestions, service.suggestTags(user, "descendant-id", "source text"));
        verify(provider).suggest("Nested notes", "text/plain", "source text");
    }

    @Test
    void scannedDescendantCanReadAndSaveExtractedText() {
        when(user.getSubject()).thenReturn("subject");
        when(user.getEmail()).thenReturn("user@example.com");
        when(user.getFullName()).thenReturn("User");
        when(accounts.upsert("subject", "user@example.com", "User")).thenReturn(accountId);
        when(resources.findFileInSelectedScope(accountId, "descendant-id"))
                .thenReturn(Optional.of(new ResourceRepository.ResourceDescriptor(
                        "descendant-id", "Nested notes", "text/plain")));
        ResourceRepository.StoredContent stored = new ResourceRepository.StoredContent("source text", Instant.now());
        when(resources.findContent(accountId, "descendant-id")).thenReturn(Optional.of(stored));
        when(resources.saveContent(accountId, "descendant-id", "updated text")).thenReturn(true);

        assertEquals(Optional.of(stored), service.get(user, "descendant-id"));
        service.save(user, "descendant-id", "updated text");

        verify(resources).saveContent(accountId, "descendant-id", "updated text");
    }

    @Test
    void fileOutsideSelectedScopeCannotUseTagSuggestions() {
        when(user.getSubject()).thenReturn("subject");
        when(user.getEmail()).thenReturn("user@example.com");
        when(user.getFullName()).thenReturn("User");
        when(accounts.upsert("subject", "user@example.com", "User")).thenReturn(accountId);
        when(resources.findFileInSelectedScope(accountId, "unscoped-file")).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.suggestTags(user, "unscoped-file", "source text"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verifyNoInteractions(provider);
    }
}
