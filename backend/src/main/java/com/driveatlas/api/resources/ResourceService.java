package com.driveatlas.api.resources;

import com.driveatlas.api.account.AccountRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

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
    public boolean delete(OidcUser user, String providerId) {
        return resources.delete(accountId(user), providerId);
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
}
