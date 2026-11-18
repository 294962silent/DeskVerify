package com.echomind.saas.rag.infrastructure;

import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.rag.application.KnowledgeDocumentRepository;
import com.echomind.saas.rag.application.KnowledgeSearchScope;
import com.echomind.saas.rag.application.LexicalSearchHit;
import com.echomind.saas.rag.domain.KnowledgeChunk;
import com.echomind.saas.rag.domain.KnowledgeDocument;
import com.echomind.saas.rag.domain.KnowledgeDocumentVersion;
import com.echomind.saas.rag.domain.KnowledgeVersionStatus;
import com.echomind.saas.rag.domain.KnowledgeVisibility;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcKnowledgeDocumentRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public KnowledgeDocument insertDocument(
            TenantId tenantId, KnowledgeDocument document) {
        jdbc.update("""
                insert into knowledge_document(
                    tenant_id, document_id, title, status,
                    current_version, created_by_user_id
                ) values (?, ?, ?, ?, ?, ?)
                """,
                tenantId.value(),
                document.documentId(),
                document.title(),
                document.status(),
                document.currentVersion(),
                document.createdByUserId());
        return document;
    }

    @Override
    public Optional<KnowledgeDocument> findDocument(
            TenantId tenantId, String documentId) {
        return jdbc.query("""
                select document_id, title, status, current_version, created_by_user_id
                from knowledge_document
                where tenant_id = ? and document_id = ?
                """, (result, row) -> new KnowledgeDocument(
                        result.getString("document_id"),
                        result.getString("title"),
                        result.getString("status"),
                        (Integer) result.getObject("current_version"),
                        result.getString("created_by_user_id")),
                tenantId.value(), documentId)
                .stream()
                .findFirst();
    }

    @Override
    public int nextVersion(TenantId tenantId, String documentId) {
        Integer next = jdbc.queryForObject("""
                select coalesce(max(version_number), 0) + 1
                from knowledge_document_version
                where tenant_id = ? and document_id = ?
                """, Integer.class, tenantId.value(), documentId);
        return next == null ? 1 : next;
    }

    @Override
    public KnowledgeDocumentVersion insertVersion(
            TenantId tenantId, KnowledgeDocumentVersion version) {
        jdbc.update("""
                insert into knowledge_document_version(
                    tenant_id, document_id, version_number, status,
                    source_checksum, normalized_text, chunk_count,
                    embedding_model, created_by_user_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId.value(),
                version.documentId(),
                version.versionNumber(),
                version.status().name(),
                version.sourceChecksum(),
                version.normalizedText(),
                version.chunkCount(),
                version.embeddingModel(),
                version.createdByUserId());
        return version;
    }

    @Override
    public Optional<KnowledgeDocumentVersion> findVersion(
            TenantId tenantId, String documentId, int versionNumber) {
        return jdbc.query(VERSION_SELECT + """
                where tenant_id = ? and document_id = ? and version_number = ?
                """, this::mapVersion,
                tenantId.value(), documentId, versionNumber)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<KnowledgeDocumentVersion> findVersionByChecksum(
            TenantId tenantId, String documentId, String checksum) {
        return jdbc.query(VERSION_SELECT + """
                where tenant_id = ? and document_id = ? and source_checksum = ?
                """, this::mapVersion,
                tenantId.value(), documentId, checksum)
                .stream()
                .findFirst();
    }

    @Override
    public List<KnowledgeDocumentVersion> listVersions(
            TenantId tenantId, String documentId) {
        return jdbc.query(VERSION_SELECT + """
                where tenant_id = ? and document_id = ?
                order by version_number desc
                """, this::mapVersion, tenantId.value(), documentId);
    }

    @Override
    public void markIndexingAndInsertChunks(
            TenantId tenantId,
            String documentId,
            int versionNumber,
            String embeddingModel,
            List<KnowledgeChunk> chunks) {
        int updated = jdbc.update("""
                update knowledge_document_version
                set status = 'INDEXING',
                    chunk_count = 0,
                    embedding_model = ?,
                    failure_reason = null
                where tenant_id = ?
                  and document_id = ?
                  and version_number = ?
                  and status in ('DRAFT', 'INDEXING', 'FAILED')
                """, embeddingModel, tenantId.value(), documentId, versionNumber);
        if (updated != 1) {
            throw new IllegalStateException("knowledge version is not indexable");
        }

        jdbc.batchUpdate("""
                insert into knowledge_chunk(
                    tenant_id, chunk_id, document_id, version_number,
                    chunk_index, document_title, content, page_number,
                    section_title, heading_path, char_start, char_end,
                    checksum, visibility, customer_id, embedding_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, chunks, 100, (PreparedStatement statement, KnowledgeChunk chunk) -> {
                    statement.setString(1, tenantId.value());
                    statement.setString(2, chunk.chunkId());
                    statement.setString(3, chunk.documentId());
                    statement.setInt(4, chunk.versionNumber());
                    statement.setInt(5, chunk.chunkIndex());
                    statement.setString(6, chunk.documentTitle());
                    statement.setString(7, chunk.content());
                    if (chunk.pageNumber() == null) {
                        statement.setObject(8, null);
                    } else {
                        statement.setInt(8, chunk.pageNumber());
                    }
                    statement.setString(9, chunk.sectionTitle());
                    statement.setString(10, chunk.headingPath());
                    statement.setInt(11, chunk.charStart());
                    statement.setInt(12, chunk.charEnd());
                    statement.setString(13, chunk.checksum());
                    statement.setString(14, chunk.visibility().name());
                    statement.setString(15, chunk.customerId());
                    statement.setString(16, writeEmbedding(chunk.embedding()));
                });

        int completed = jdbc.update("""
                update knowledge_document_version
                set status = 'DRAFT',
                    chunk_count = ?,
                    indexed_at = current_timestamp(6)
                where tenant_id = ?
                  and document_id = ?
                  and version_number = ?
                  and status = 'INDEXING'
                """, chunks.size(), tenantId.value(), documentId, versionNumber);
        if (completed != 1) {
            throw new IllegalStateException("knowledge indexing completion failed");
        }
    }

    @Override
    public void publish(
            TenantId tenantId,
            String documentId,
            int versionNumber,
            int expectedChunkCount,
            String embeddingModel) {
        KnowledgeDocumentVersion version = jdbc.query(VERSION_SELECT + """
                where tenant_id = ? and document_id = ? and version_number = ?
                for update
                """, this::mapVersion,
                tenantId.value(), documentId, versionNumber)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("knowledge version not found"));
        if (version.status() != KnowledgeVersionStatus.DRAFT) {
            throw new IllegalStateException("only a DRAFT version can be published");
        }
        Integer actualCount = jdbc.queryForObject("""
                select count(*) from knowledge_chunk
                where tenant_id = ? and document_id = ? and version_number = ?
                """, Integer.class, tenantId.value(), documentId, versionNumber);
        if (actualCount == null
                || actualCount != expectedChunkCount
                || version.chunkCount() != expectedChunkCount
                || !version.embeddingModel().equals(embeddingModel)) {
            throw new IllegalStateException("knowledge index verification failed");
        }

        jdbc.update("""
                update knowledge_document_version
                set status = 'RETIRED'
                where tenant_id = ?
                  and document_id = ?
                  and status = 'PUBLISHED'
                  and version_number <> ?
                """, tenantId.value(), documentId, versionNumber);
        jdbc.update("""
                update knowledge_document_version
                set status = 'PUBLISHED',
                    published_at = current_timestamp(6)
                where tenant_id = ?
                  and document_id = ?
                  and version_number = ?
                  and status = 'DRAFT'
                """, tenantId.value(), documentId, versionNumber);
        jdbc.update("""
                update knowledge_document
                set current_version = ?,
                    status = 'ACTIVE'
                where tenant_id = ? and document_id = ?
                """, versionNumber, tenantId.value(), documentId);
    }

    @Override
    public List<LexicalSearchHit> lexicalSearch(
            KnowledgeSearchScope scope, String query, int limit) {
        List<Object> arguments = new ArrayList<>();
        arguments.add(scope.tenantId().value());
        arguments.add(query);
        String acl = aclClause("c", scope, arguments);
        arguments.add(limit);
        return jdbc.query("""
                select c.chunk_id, c.document_id, c.version_number, c.chunk_index,
                       c.document_title, c.content, c.page_number, c.section_title,
                       c.heading_path, c.char_start, c.char_end, c.checksum,
                       c.visibility, c.customer_id, c.embedding_json,
                       match(c.content, c.document_title, c.section_title)
                           against (? in natural language mode) as lexical_score
                from knowledge_chunk c
                join knowledge_document d
                  on d.tenant_id = c.tenant_id and d.document_id = c.document_id
                 and d.current_version = c.version_number
                 and d.status = 'ACTIVE'
                join knowledge_document_version v
                  on v.tenant_id = c.tenant_id
                 and v.document_id = c.document_id
                 and v.version_number = c.version_number
                 and v.status = 'PUBLISHED'
                where c.tenant_id = ?
                  and match(c.content, c.document_title, c.section_title)
                      against (? in natural language mode) > 0
                %s
                """.formatted(acl) + """
                order by lexical_score desc, c.document_id, c.chunk_index
                limit ?
                """,
                (result, row) -> new LexicalSearchHit(
                        mapChunk(result),
                        result.getDouble("lexical_score")),
                reorderLexicalArguments(arguments, scope, query, limit))
                .stream()
                .toList();
    }

    @Override
    public List<KnowledgeChunk> activeChunks(KnowledgeSearchScope scope) {
        List<Object> arguments = new ArrayList<>();
        arguments.add(scope.tenantId().value());
        String acl = aclClause("c", scope, arguments);
        return jdbc.query("""
                select c.chunk_id, c.document_id, c.version_number, c.chunk_index,
                       c.document_title, c.content, c.page_number, c.section_title,
                       c.heading_path, c.char_start, c.char_end, c.checksum,
                       c.visibility, c.customer_id, c.embedding_json
                from knowledge_chunk c
                join knowledge_document d
                  on d.tenant_id = c.tenant_id and d.document_id = c.document_id
                 and d.current_version = c.version_number
                 and d.status = 'ACTIVE'
                join knowledge_document_version v
                  on v.tenant_id = c.tenant_id
                 and v.document_id = c.document_id
                 and v.version_number = c.version_number
                 and v.status = 'PUBLISHED'
                where c.tenant_id = ?%s
                """.formatted(acl)
                        + " order by c.document_id, c.chunk_index",
                (result, row) -> mapChunk(result),
                arguments.toArray());
    }

    @Override
    public long countPublishedChunks(TenantId tenantId) {
        Long count = jdbc.queryForObject("""
                select count(*)
                from knowledge_chunk c
                join knowledge_document d
                  on d.tenant_id = c.tenant_id and d.document_id = c.document_id
                 and d.current_version = c.version_number
                 and d.status = 'ACTIVE'
                join knowledge_document_version v
                  on v.tenant_id = c.tenant_id
                 and v.document_id = c.document_id
                 and v.version_number = c.version_number
                 and v.status = 'PUBLISHED'
                where c.tenant_id = ?
                """, Long.class, tenantId.value());
        return count == null ? 0 : count;
    }

    private KnowledgeDocumentVersion mapVersion(ResultSet result, int row) throws SQLException {
        return new KnowledgeDocumentVersion(
                result.getString("document_id"),
                result.getInt("version_number"),
                KnowledgeVersionStatus.valueOf(result.getString("status")),
                result.getString("source_checksum"),
                result.getString("normalized_text"),
                result.getInt("chunk_count"),
                result.getString("embedding_model"),
                result.getString("created_by_user_id"),
                instant(result.getTimestamp("published_at")));
    }

    private KnowledgeChunk mapChunk(ResultSet result) throws SQLException {
        return new KnowledgeChunk(
                result.getString("chunk_id"),
                result.getString("document_id"),
                result.getInt("version_number"),
                result.getInt("chunk_index"),
                result.getString("document_title"),
                result.getString("content"),
                (Integer) result.getObject("page_number"),
                result.getString("section_title"),
                result.getString("heading_path"),
                result.getInt("char_start"),
                result.getInt("char_end"),
                result.getString("checksum"),
                KnowledgeVisibility.valueOf(result.getString("visibility")),
                result.getString("customer_id"),
                readEmbedding(result.getString("embedding_json")));
    }

    private String writeEmbedding(double[] embedding) {
        try {
            return objectMapper.writeValueAsString(embedding);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("embedding serialization failed", exception);
        }
    }

    private double[] readEmbedding(String json) {
        try {
            List<Double> values = objectMapper.readValue(json, new TypeReference<>() {
            });
            double[] embedding = new double[values.size()];
            for (int index = 0; index < values.size(); index++) {
                embedding[index] = values.get(index);
            }
            return embedding;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored embedding is invalid", exception);
        }
    }

    private static String aclClause(
            String alias, KnowledgeSearchScope scope, List<Object> arguments) {
        if (scope.tenantWide()) {
            return "";
        }
        if (scope.customerIds().isEmpty()) {
            return " and " + alias + ".visibility = 'TENANT'";
        }
        String placeholders = String.join(", ",
                java.util.Collections.nCopies(scope.customerIds().size(), "?"));
        arguments.addAll(scope.customerIds());
        return " and (" + alias + ".visibility = 'TENANT' or ("
                + alias + ".visibility = 'CUSTOMER' and "
                + alias + ".customer_id in (" + placeholders + ")))";
    }

    private static Object[] reorderLexicalArguments(
            List<Object> ignored,
            KnowledgeSearchScope scope,
            String query,
            int limit) {
        List<Object> values = new ArrayList<>();
        values.add(query);
        values.add(scope.tenantId().value());
        values.add(query);
        if (!scope.tenantWide()) {
            values.addAll(scope.customerIds());
        }
        values.add(limit);
        return values.toArray();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static final String VERSION_SELECT = """
            select document_id, version_number, status, source_checksum,
                   normalized_text, chunk_count, embedding_model,
                   created_by_user_id, published_at
            from knowledge_document_version
            """;
}
