CREATE TABLE drive_resource_scope (
    account_id UUID NOT NULL,
    root_provider_id VARCHAR(512) NOT NULL,
    provider_id VARCHAR(512) NOT NULL,
    PRIMARY KEY (account_id, root_provider_id, provider_id),
    FOREIGN KEY (account_id, root_provider_id)
        REFERENCES drive_resource(account_id, provider_id) ON DELETE CASCADE,
    FOREIGN KEY (account_id, provider_id)
        REFERENCES drive_resource(account_id, provider_id) ON DELETE CASCADE
);

INSERT INTO drive_resource_scope (account_id, root_provider_id, provider_id)
SELECT account_id, provider_id, provider_id
FROM drive_resource;

CREATE INDEX drive_resource_scope_member_idx
    ON drive_resource_scope (account_id, provider_id);
