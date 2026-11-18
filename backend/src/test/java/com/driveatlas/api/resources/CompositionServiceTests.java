package com.driveatlas.api.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.driveatlas.api.account.AccountRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.server.ResponseStatusException;

class CompositionServiceTests {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final ResourceRepository resources = mock(ResourceRepository.class);
    private final CompositionProvider provider = mock(CompositionProvider.class);
    private final CompositionService service = new CompositionService(accounts, resources, provider);
    private final OidcUser user = mock(OidcUser.class);
    private final UUID accountId = UUID.randomUUID();

    @Test
    void composesOnlySelectedSupportedFilesAndUsesServerResourceNames() {
        authorizeAccount();
        when(resources.findFileInSelectedScope(accountId, "file-1"))
                .thenReturn(Optional.of(new ResourceRepository.ResourceDescriptor(
                        "file-1", "Strategies", "text/plain")));
        when(resources.findFileInSelectedScope(accountId, "file-2"))
                .thenReturn(Optional.of(new ResourceRepository.ResourceDescriptor(
                        "file-2", "Invalidation", "text/markdown")));
        CompositionResult expected = new CompositionResult("Caching", List.of(), List.of());
        when(provider.compose(List.of(
                new CompositionSource("S1", "Strategies", "text/plain", "strategy source text"),
                new CompositionSource("S2", "Invalidation", "text/markdown", "invalidation source text")),
                CompositionMode.SUMMARIZE))
                .thenReturn(expected);
        CompositionRequest request = new CompositionRequest(List.of(
                new CompositionSourceRequest("file-1", "strategy source text"),
                new CompositionSourceRequest("file-2", "invalidation source text")));

        assertEquals(expected, service.compose(user, request));
        verify(provider).compose(List.of(
                new CompositionSource("S1", "Strategies", "text/plain", "strategy source text"),
                new CompositionSource("S2", "Invalidation", "text/markdown", "invalidation source text")),
                CompositionMode.SUMMARIZE);
    }

    @Test
    void forwardsFullMergeModeToTheProvider() {
        authorizeAccount();
        when(resources.findFileInSelectedScope(accountId, "file-1"))
                .thenReturn(Optional.of(new ResourceRepository.ResourceDescriptor(
                        "file-1", "Strategies", "text/plain")));
        when(resources.findFileInSelectedScope(accountId, "file-2"))
                .thenReturn(Optional.of(new ResourceRepository.ResourceDescriptor(
                        "file-2", "Invalidation", "text/markdown")));
        CompositionResult expected = new CompositionResult("Caching", List.of(), List.of());
        List<CompositionSource> submittedSources = List.of(
                new CompositionSource("S1", "Strategies", "text/plain", "strategy source text"),
                new CompositionSource("S2", "Invalidation", "text/markdown", "invalidation source text"));
        when(provider.compose(submittedSources, CompositionMode.FULL)).thenReturn(expected);

        assertEquals(expected, service.compose(user, new CompositionRequest(
                CompositionMode.FULL,
                List.of(
                        new CompositionSourceRequest("file-1", "strategy source text"),
                        new CompositionSourceRequest("file-2", "invalidation source text")))));

        verify(provider).compose(submittedSources, CompositionMode.FULL);
    }

    @Test
    void rejectsAnySourceOutsideTheAuthenticatedUsersSelectedScope() {
        authorizeAccount();
        when(resources.findFileInSelectedScope(accountId, "file-1"))
                .thenReturn(Optional.of(new ResourceRepository.ResourceDescriptor(
                        "file-1", "Strategies", "text/plain")));
        when(resources.findFileInSelectedScope(accountId, "unscoped-file")).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.compose(user, new CompositionRequest(List.of(
                        new CompositionSourceRequest("file-1", "strategy source text"),
                        new CompositionSourceRequest("unscoped-file", "unscoped source text")))));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verifyNoInteractions(provider);
    }

    @Test
    void rejectsUnsupportedFiles() {
        authorizeAccount();
        when(resources.findFileInSelectedScope(accountId, "pdf-file"))
                .thenReturn(Optional.of(new ResourceRepository.ResourceDescriptor(
                        "pdf-file", "Scanned document", "application/pdf")));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.compose(user, new CompositionRequest(List.of(
                        new CompositionSourceRequest("pdf-file", "some extracted text"),
                        new CompositionSourceRequest("pdf-file-2", "other text")))));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(provider);
    }

    @Test
    void rejectsCombinedSourceTextAboveTheLimit() {
        authorizeAccount();
        when(resources.findFileInSelectedScope(accountId, "file-1"))
                .thenReturn(Optional.of(new ResourceRepository.ResourceDescriptor(
                        "file-1", "First", "text/plain")));
        when(resources.findFileInSelectedScope(accountId, "file-2"))
                .thenReturn(Optional.of(new ResourceRepository.ResourceDescriptor(
                        "file-2", "Second", "text/plain")));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.compose(user, new CompositionRequest(List.of(
                        new CompositionSourceRequest("file-1", "a".repeat(30_001)),
                        new CompositionSourceRequest("file-2", "b".repeat(30_000))))));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exception.getStatusCode());
        verifyNoInteractions(provider);
    }

    private void authorizeAccount() {
        when(user.getSubject()).thenReturn("subject");
        when(user.getEmail()).thenReturn("user@example.com");
        when(user.getFullName()).thenReturn("User");
        when(accounts.upsert("subject", "user@example.com", "User")).thenReturn(accountId);
    }
}
