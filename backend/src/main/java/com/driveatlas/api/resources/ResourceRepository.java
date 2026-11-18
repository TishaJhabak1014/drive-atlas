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
    private static final String UPSERT_RESOURCE = """
            INSERT INTO drive_resource (
                account_id, provider_id, resource_type, name, mime_type,
                provider_modified_at, web_view_link, parent_provider_id, metadata_refreshed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (account_id, provider_id) DO UPDATE SET
                resource_type = EXCLUDED.resource_type,
                name = EXCLUDED.name,
                mime_type = EXCLUDED.mime_type,
                provider_modified_at = EXCLUDED.provider_modified_at,
                web_view_link = EXCLUDED.web_view_link,
                parent_provider_id = EXCLUDED.parent_provider_id,
                metadata_refreshed_at = CURRENT_TIMESTAMP
            """;

    private final JdbcTemplate jdbc;

    public ResourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(UUID accountId, ResourceSelection selection) {
        upsertResource(accountId, selection);
        jdbc.update("""
                INSERT INTO drive_resource_scope (account_id, root_provider_id, provider_id)
                VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """, accountId, selection.providerId(), selection.providerId());
    }

    public boolean replaceFolderContents(UUID accountId, String rootProviderId, List<ResourceSelection> scannedResources) {
        if (!isSelectedFolder(accountId, rootProviderId)) {
            return false;
        }

        jdbc.batchUpdate(UPSERT_RESOURCE, scannedResources, 500, (statement, resource) ->
                bindResource(statement, accountId, resource));
        jdbc.update("""
                DELETE FROM drive_resource_scope
                WHERE account_id = ? AND root_provider_id = ? AND provider_id <> ?
                """, accountId, rootProviderId, rootProviderId);

        List<ResourceSelection> children = scannedResources.stream()
                .filter(resource -> !resource.providerId().equals(rootProviderId))
                .toList();
        jdbc.batchUpdate("""
                INSERT INTO drive_resource_scope (account_id, root_provider_id, provider_id)
                VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """, children, 500, (statement, resource) -> {
                    statement.setObject(1, accountId);
                    statement.setString(2, rootProviderId);
                    statement.setString(3, resource.providerId());
                });
        pruneUnscopedResourcesAndTags(accountId);
        return true;
    }

    public List<SelectedResource> findAll(UUID accountId) {
        List<SelectedResource> resources = jdbc.query("""
                SELECT resource.provider_id, resource.resource_type, resource.name, resource.mime_type,
                       resource.provider_modified_at, resource.web_view_link, resource.parent_provider_id,
                       resource.selected_at, resource.metadata_refreshed_at, resource.indexing_status,
                       content.extracted_at,
                       content.provider_id IS NOT NULL AS has_stored_text,
                       EXISTS (
                           SELECT 1 FROM drive_resource_scope root_scope
                           WHERE root_scope.account_id = resource.account_id
                             AND root_scope.root_provider_id = resource.provider_id
                       ) AS explicitly_selected
                FROM drive_resource resource
                LEFT JOIN drive_resource_content content
                  ON content.account_id = resource.account_id
                 AND content.provider_id = resource.provider_id
                WHERE resource.account_id = ?
                  AND EXISTS (
                      SELECT 1 FROM drive_resource_scope member_scope
                      WHERE member_scope.account_id = resource.account_id
                        AND member_scope.provider_id = resource.provider_id
                  )
                ORDER BY resource.resource_type, resource.name
                """, (rs, rowNum) -> new SelectedResource(
                        rs.getString("provider_id"),
                        ResourceSelection.ResourceType.valueOf(rs.getString("resource_type")),
                        rs.getString("name"),
                        rs.getString("mime_type"),
                        rs.getTimestamp("provider_modified_at") == null ? null : rs.getTimestamp("provider_modified_at").toInstant(),
                        rs.getString("web_view_link"),
                        rs.getString("parent_provider_id"),
                        rs.getTimestamp("selected_at").toInstant(),
                        rs.getTimestamp("metadata_refreshed_at") == null
                                ? null : rs.getTimestamp("metadata_refreshed_at").toInstant(),
                        rs.getTimestamp("extracted_at") == null
                                ? null : rs.getTimestamp("extracted_at").toInstant(),
                        rs.getBoolean("has_stored_text"),
                        rs.getString("indexing_status"),
                        rs.getBoolean("explicitly_selected"),
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
                        resource.metadataRefreshedAt(),
                        resource.contentExtractedAt(),
                        resource.hasStoredText(),
                        resource.indexingStatus(),
                        resource.explicitlySelected(),
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

    public Optional<ResourceDescriptor> findFileInSelectedScope(UUID accountId, String providerId) {
        return jdbc.query("""
                SELECT resource.provider_id, resource.name, resource.mime_type
                FROM drive_resource resource
                JOIN drive_resource_scope member_scope
                  ON member_scope.account_id = resource.account_id
                 AND member_scope.provider_id = resource.provider_id
                JOIN drive_resource selected_root
                  ON selected_root.account_id = member_scope.account_id
                 AND selected_root.provider_id = member_scope.root_provider_id
                JOIN drive_resource_scope root_scope
                  ON root_scope.account_id = selected_root.account_id
                 AND root_scope.root_provider_id = selected_root.provider_id
                 AND root_scope.provider_id = selected_root.provider_id
                WHERE resource.account_id = ?
                  AND resource.provider_id = ?
                  AND resource.resource_type = 'FILE'
                  AND selected_root.resource_type IN ('FILE', 'FOLDER')
                """, (rs, rowNum) -> new ResourceDescriptor(
                        rs.getString("provider_id"),
                        rs.getString("name"),
                        rs.getString("mime_type")), accountId, providerId)
                .stream()
                .findFirst();
    }

    public Optional<StoredContent> findContent(UUID accountId, String providerId) {
        return jdbc.query("""
                SELECT extracted_text, extracted_at
                FROM drive_resource_content
                WHERE account_id = ? AND provider_id = ?
                """, (rs, rowNum) -> new StoredContent(
                        rs.getString("extracted_text"),
                        rs.getTimestamp("extracted_at").toInstant()), accountId, providerId)
                .stream()
                .findFirst();
    }

    public boolean saveContent(UUID accountId, String providerId, String text) {
        if (findFileInSelectedScope(accountId, providerId).isEmpty()) {
            return false;
        }
        jdbc.update("""
                INSERT INTO drive_resource_content (account_id, provider_id, extracted_text)
                VALUES (?, ?, ?)
                ON CONFLICT (account_id, provider_id) DO UPDATE SET
                    extracted_text = EXCLUDED.extracted_text,
                    extracted_at = CURRENT_TIMESTAMP
                """, accountId, providerId, text);
        jdbc.update("""
                UPDATE drive_resource SET indexing_status = 'READY'
                WHERE account_id = ? AND provider_id = ?
                """, accountId, providerId);
        return true;
    }

    public void deleteContent(UUID accountId, String providerId) {
        jdbc.update("""
                DELETE FROM drive_resource_content
                WHERE account_id = ? AND provider_id = ?
                """, accountId, providerId);
        jdbc.update("""
                UPDATE drive_resource SET indexing_status = 'NOT_REQUESTED'
                WHERE account_id = ? AND provider_id = ?
                """, accountId, providerId);
    }

    private void upsertResource(UUID accountId, ResourceSelection selection) {
        jdbc.update(UPSERT_RESOURCE,
                accountId,
                selection.providerId(),
                selection.resourceType().name(),
                selection.name(),
                selection.mimeType(),
                selection.modifiedTime() == null ? null : Timestamp.from(selection.modifiedTime()),
                selection.webViewLink(),
                selection.parentProviderId());
    }

    private void bindResource(
            java.sql.PreparedStatement statement,
            UUID accountId,
            ResourceSelection selection) throws java.sql.SQLException {
        statement.setObject(1, accountId);
        statement.setString(2, selection.providerId());
        statement.setString(3, selection.resourceType().name());
        statement.setString(4, selection.name());
        statement.setString(5, selection.mimeType());
        statement.setTimestamp(6, selection.modifiedTime() == null ? null : Timestamp.from(selection.modifiedTime()));
        statement.setString(7, selection.webViewLink());
        statement.setString(8, selection.parentProviderId());
    }

    private boolean isSelectedFolder(UUID accountId, String providerId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM drive_resource resource
                    JOIN drive_resource_scope scope
                      ON scope.account_id = resource.account_id
                     AND scope.root_provider_id = resource.provider_id
                    WHERE resource.account_id = ?
                      AND resource.provider_id = ?
                      AND resource.resource_type = 'FOLDER'
                )
                """, Boolean.class, accountId, providerId);
        return Boolean.TRUE.equals(exists);
    }

    private boolean exists(UUID accountId, String providerId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM drive_resource_scope
                    WHERE account_id = ? AND provider_id = ?
                )
                """, Boolean.class, accountId, providerId);
        return Boolean.TRUE.equals(exists);
    }

    public boolean delete(UUID accountId, String providerId) {
        boolean removed = jdbc.update("""
                DELETE FROM drive_resource_scope
                WHERE account_id = ? AND root_provider_id = ?
                """, accountId, providerId) > 0;
        if (removed) {
            pruneUnscopedResourcesAndTags(accountId);
        }
        return removed;
    }

    private void pruneUnscopedResourcesAndTags(UUID accountId) {
        jdbc.update("""
                DELETE FROM drive_resource resource
                WHERE resource.account_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM drive_resource_scope scope
                      WHERE scope.account_id = resource.account_id
                        AND scope.provider_id = resource.provider_id
                  )
                """, accountId);
        jdbc.update("""
                DELETE FROM drive_tag tag
                WHERE tag.account_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM drive_resource_tag assignment
                      WHERE assignment.account_id = tag.account_id AND assignment.tag_id = tag.id
                  )
                """, accountId);
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
            java.time.Instant metadataRefreshedAt,
            java.time.Instant contentExtractedAt,
            boolean hasStoredText,
            String indexingStatus,
            boolean explicitlySelected,
            List<ResourceTag> tags) {
    }

    public record ResourceTag(UUID id, String name) {
    }

    public record ResourceDescriptor(String providerId, String name, String mimeType) {
    }

    public record StoredContent(String text, java.time.Instant extractedAt) {
    }
}
