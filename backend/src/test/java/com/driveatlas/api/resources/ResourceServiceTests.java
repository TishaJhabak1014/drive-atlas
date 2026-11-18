package com.driveatlas.api.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.driveatlas.api.account.AccountRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ResourceServiceTests {
    @Test
    void folderScanAcceptsNestedResourcesUnderTheSelectedRoot() {
        AccountRepository accounts = mock(AccountRepository.class);
        ResourceRepository resources = mock(ResourceRepository.class);
        OidcUser user = mock(OidcUser.class);
        UUID accountId = UUID.randomUUID();
        when(user.getSubject()).thenReturn("subject");
        when(user.getEmail()).thenReturn("user@example.com");
        when(user.getFullName()).thenReturn("User");
        when(accounts.upsert("subject", "user@example.com", "User")).thenReturn(accountId);
        when(resources.replaceFolderContents(eq(accountId), eq("root"), anyList())).thenReturn(true);
        ResourceService service = new ResourceService(accounts, resources);
        ResourceSelection root = new ResourceSelection(
                "root", "Selected folder", "application/vnd.google-apps.folder",
                ResourceSelection.ResourceType.FOLDER, null, null, null);
        ResourceSelection childFolder = new ResourceSelection(
                "child-folder", "Child folder", "application/vnd.google-apps.folder",
                ResourceSelection.ResourceType.FOLDER, null, null, "root");
        ResourceSelection childFile = new ResourceSelection(
                "child-file", "File", "text/plain", ResourceSelection.ResourceType.FILE,
                null, null, "child-folder");

        assertTrue(service.saveFolderScan(user, "root", List.of(root, childFolder, childFile)));
        verify(resources).replaceFolderContents(accountId, "root", List.of(root, childFolder, childFile));
    }

    @Test
    void folderScanRejectsResourcesOutsideTheSelectedRoot() {
        ResourceService service = new ResourceService(mock(com.driveatlas.api.account.AccountRepository.class),
                mock(ResourceRepository.class));
        ResourceSelection root = new ResourceSelection(
                "root", "Selected folder", "application/vnd.google-apps.folder",
                ResourceSelection.ResourceType.FOLDER, null, null, null);
        ResourceSelection child = new ResourceSelection(
                "child", "File", "text/plain", ResourceSelection.ResourceType.FILE,
                null, null, "unrelated-folder");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.saveFolderScan(null, "root", List.of(root, child)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }
}
