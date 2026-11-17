CREATE TABLE google_account (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    google_subject VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(320) NOT NULL,
    display_name VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE drive_resource (
    account_id UUID NOT NULL REFERENCES google_account(id) ON DELETE CASCADE,
    provider_id VARCHAR(512) NOT NULL,
    resource_type VARCHAR(16) NOT NULL CHECK (resource_type IN ('FILE', 'FOLDER')),
    name VARCHAR(1000) NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    provider_modified_at TIMESTAMPTZ,
    web_view_link TEXT,
    parent_provider_id VARCHAR(512),
    selected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    indexing_status VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUESTED'
        CHECK (indexing_status IN ('NOT_REQUESTED', 'QUEUED', 'PROCESSING', 'READY', 'UNSUPPORTED', 'FAILED')),
    PRIMARY KEY (account_id, provider_id)
);

CREATE INDEX drive_resource_account_parent_idx ON drive_resource (account_id, parent_provider_id);
