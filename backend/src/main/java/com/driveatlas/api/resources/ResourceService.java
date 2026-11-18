package com.driveatlas.api.resources;

import com.driveatlas.api.account.AccountRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ResourceService {
    private final AccountRepository accounts;
    private final ResourceRepository resources;

    public ResourceService(AccountRepository accounts, ResourceRepository resources) {
        this.accounts = accounts;
        this.resources = resources;
    }

    @Transactional
    public List<ResourceRepository.SelectedResource> list(OidcUser user) {
        return resources.findAll(accountId(user));
    }

    @Transactional
    public void save(OidcUser user, ResourceSelection selection) {
        resources.save(accountId(user), selection);
    }

    @Transactional
    public boolean saveFolderScan(OidcUser user, String rootProviderId, List<ResourceSelection> scannedResources) {
        validateFolderTree(rootProviderId, scannedResources);
        return resources.replaceFolderContents(accountId(user), rootProviderId, scannedResources);
    }

    @Transactional
    public boolean delete(OidcUser user, String providerId) {
        return resources.delete(accountId(user), providerId);
    }

    @Transactional
    public Optional<ResourceRepository.ResourceTag> addTag(OidcUser user, String providerId, String name) {
        return resources.addTag(accountId(user), providerId, name);
    }

    @Transactional
    public boolean removeTag(OidcUser user, String providerId, UUID tagId) {
        return resources.removeTag(accountId(user), providerId, tagId);
    }

    @Transactional
    public void deleteAccount(OidcUser user) {
        accounts.delete(user.getSubject());
    }

    private java.util.UUID accountId(OidcUser user) {
        return accounts.upsert(
                user.getSubject(),
                user.getEmail(),
                user.getFullName());
    }

    private void validateFolderTree(String rootProviderId, List<ResourceSelection> scannedResources) {
        Map<String, ResourceSelection> resourcesById = new HashMap<>();
        for (ResourceSelection resource : scannedResources) {
            if (resourcesById.putIfAbsent(resource.providerId(), resource) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Folder scan contains duplicate resources");
            }
        }

        ResourceSelection root = resourcesById.get(rootProviderId);
        if (root == null || root.resourceType() != ResourceSelection.ResourceType.FOLDER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Folder scan must include its selected root");
        }

        Set<String> folderIds = new HashSet<>();
        resourcesById.values().stream()
                .filter(resource -> resource.resourceType() == ResourceSelection.ResourceType.FOLDER)
                .forEach(resource -> folderIds.add(resource.providerId()));
        for (ResourceSelection resource : scannedResources) {
            if (resource.providerId().equals(rootProviderId)) {
                continue;
            }
            String parentId = resource.parentProviderId();
            Set<String> visited = new HashSet<>();
            while (parentId != null && !parentId.equals(rootProviderId) && visited.add(parentId)) {
                ResourceSelection parent = resourcesById.get(parentId);
                if (parent == null || parent.resourceType() != ResourceSelection.ResourceType.FOLDER) {
                    break;
                }
                parentId = parent.parentProviderId();
            }
            if (!rootProviderId.equals(parentId) || !folderIds.contains(resource.parentProviderId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Folder scan contains an invalid parent relationship");
            }
        }
    }
}
