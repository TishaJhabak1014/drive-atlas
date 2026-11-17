package com.driveatlas.api.resources;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ResourceRepository {
    private final JdbcTemplate jdbc;

    public ResourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(UUID accountId, ResourceSelection selection) {
        jdbc.update("""
                INSERT INTO drive_resource (
                    account_id, provider_id, resource_type, name, mime_type,
                    provider_modified_at, web_view_link, parent_provider_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (account_id, provider_id) DO UPDATE SET
                    resource_type = EXCLUDED.resource_type,
                    name = EXCLUDED.name,
                    mime_type = EXCLUDED.mime_type,
                    provider_modified_at = EXCLUDED.provider_modified_at,
                    web_view_link = EXCLUDED.web_view_link,
                    parent_provider_id = EXCLUDED.parent_provider_id
                """,
                accountId,
                selection.providerId(),
                selection.resourceType().name(),
                selection.name(),
                selection.mimeType(),
                selection.modifiedTime() == null ? null : Timestamp.from(selection.modifiedTime()),
                selection.webViewLink(),
                selection.parentProviderId());
    }

    public List<SelectedResource> findAll(UUID accountId) {
        return jdbc.query("""
                SELECT provider_id, resource_type, name, mime_type, provider_modified_at,
                       web_view_link, parent_provider_id, selected_at, indexing_status
                FROM drive_resource
                WHERE account_id = ?
                ORDER BY resource_type, name
                """, (rs, rowNum) -> new SelectedResource(
                        rs.getString("provider_id"),
                        ResourceSelection.ResourceType.valueOf(rs.getString("resource_type")),
                        rs.getString("name"),
                        rs.getString("mime_type"),
                        rs.getTimestamp("provider_modified_at") == null ? null : rs.getTimestamp("provider_modified_at").toInstant(),
                        rs.getString("web_view_link"),
                        rs.getString("parent_provider_id"),
                        rs.getTimestamp("selected_at").toInstant(),
                        rs.getString("indexing_status")), accountId);
    }

    public boolean delete(UUID accountId, String providerId) {
        return jdbc.update("DELETE FROM drive_resource WHERE account_id = ? AND provider_id = ?",
                accountId, providerId) > 0;
    }

    public record SelectedResource(
            String providerId,
            ResourceSelection.ResourceType resourceType,
            String name,
            String mimeType,
            java.time.Instant modifiedTime,
            String webViewLink,
            String parentProviderId,
            java.time.Instant selectedAt,
            String indexingStatus) {
    }
}
