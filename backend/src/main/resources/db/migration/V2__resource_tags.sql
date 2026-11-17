CREATE TABLE drive_tag (
    account_id UUID NOT NULL REFERENCES google_account(id) ON DELETE CASCADE,
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    name VARCHAR(80) NOT NULL,
    normalized_name VARCHAR(80) NOT NULL,
    PRIMARY KEY (account_id, id),
    UNIQUE (account_id, normalized_name)
);

CREATE TABLE drive_resource_tag (
    account_id UUID NOT NULL,
    provider_id VARCHAR(512) NOT NULL,
    tag_id UUID NOT NULL,
    PRIMARY KEY (account_id, provider_id, tag_id),
    FOREIGN KEY (account_id, provider_id)
        REFERENCES drive_resource(account_id, provider_id) ON DELETE CASCADE,
    FOREIGN KEY (account_id, tag_id)
        REFERENCES drive_tag(account_id, id) ON DELETE CASCADE
);

CREATE INDEX drive_resource_tag_lookup_idx
    ON drive_resource_tag (account_id, tag_id);
