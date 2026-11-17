package com.driveatlas.api.resources;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
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
        List<SelectedResource> resources = jdbc.query("""
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
                        rs.getString("indexing_status"),
                        List.of()), accountId);
        if (resources.isEmpty()) {
            return resources;
        }

        Map<String, List<ResourceTag>> tagsByResource = new HashMap<>();
        jdbc.query("""
                SELECT assignment.provider_id, tag.id, tag.name
                FROM drive_resource_tag assignment
                JOIN drive_tag tag
                  ON tag.account_id = assignment.account_id AND tag.id = assignment.tag_id
                WHERE assignment.account_id = ?
                ORDER BY tag.name
                """, (RowCallbackHandler) rs -> tagsByResource.computeIfAbsent(
                        rs.getString("provider_id"), ignored -> new ArrayList<>())
                        .add(new ResourceTag(rs.getObject("id", UUID.class), rs.getString("name"))),
                accountId);

        return resources.stream()
                .map(resource -> new SelectedResource(
                        resource.providerId(),
                        resource.resourceType(),
                        resource.name(),
                        resource.mimeType(),
                        resource.modifiedTime(),
                        resource.webViewLink(),
                        resource.parentProviderId(),
                        resource.selectedAt(),
                        resource.indexingStatus(),
                        tagsByResource.getOrDefault(resource.providerId(), List.of())))
                .toList();
    }

    public Optional<ResourceTag> addTag(UUID accountId, String providerId, String name) {
        if (!exists(accountId, providerId)) {
            return Optional.empty();
        }

        String normalizedName = name.trim().toLowerCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO drive_tag (account_id, name, normalized_name)
                VALUES (?, ?, ?)
                ON CONFLICT (account_id, normalized_name) DO NOTHING
                """, accountId, name.trim(), normalizedName);

        ResourceTag tag = jdbc.queryForObject("""
                SELECT id, name FROM drive_tag
                WHERE account_id = ? AND normalized_name = ?
                """, (rs, rowNum) -> new ResourceTag(rs.getObject("id", UUID.class), rs.getString("name")),
                accountId, normalizedName);
        jdbc.update("""
                INSERT INTO drive_resource_tag (account_id, provider_id, tag_id)
                VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """, accountId, providerId, tag.id());
        return Optional.of(tag);
    }

    public boolean removeTag(UUID accountId, String providerId, UUID tagId) {
        boolean removed = jdbc.update("""
                DELETE FROM drive_resource_tag
                WHERE account_id = ? AND provider_id = ? AND tag_id = ?
                """, accountId, providerId, tagId) > 0;
        if (removed) {
            jdbc.update("""
                    DELETE FROM drive_tag tag
                    WHERE account_id = ? AND id = ?
                      AND NOT EXISTS (
                          SELECT 1 FROM drive_resource_tag assignment
                          WHERE assignment.account_id = tag.account_id AND assignment.tag_id = tag.id
                      )
                    """, accountId, tagId);
        }
        return removed;
    }

    private boolean exists(UUID accountId, String providerId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM drive_resource
                    WHERE account_id = ? AND provider_id = ?
                )
                """, Boolean.class, accountId, providerId);
        return Boolean.TRUE.equals(exists);
    }

    public boolean delete(UUID accountId, String providerId) {
        boolean removed = jdbc.update("DELETE FROM drive_resource WHERE account_id = ? AND provider_id = ?",
                accountId, providerId) > 0;
        if (removed) {
            jdbc.update("""
                    DELETE FROM drive_tag tag
                    WHERE tag.account_id = ?
                      AND NOT EXISTS (
                          SELECT 1 FROM drive_resource_tag assignment
                          WHERE assignment.account_id = tag.account_id AND assignment.tag_id = tag.id
                      )
                    """, accountId);
        }
        return removed;
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
            String indexingStatus,
            List<ResourceTag> tags) {
    }

    public record ResourceTag(UUID id, String name) {
    }
}
