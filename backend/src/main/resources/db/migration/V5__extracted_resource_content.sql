CREATE TABLE drive_resource_content (
    account_id UUID NOT NULL,
    provider_id VARCHAR(512) NOT NULL,
    extracted_text TEXT NOT NULL,
    extracted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, provider_id),
    FOREIGN KEY (account_id, provider_id)
        REFERENCES drive_resource(account_id, provider_id) ON DELETE CASCADE
);
