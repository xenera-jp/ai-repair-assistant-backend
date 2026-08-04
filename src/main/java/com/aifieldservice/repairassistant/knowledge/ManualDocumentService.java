package com.aifieldservice.repairassistant.knowledge;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.aifieldservice.repairassistant.config.RepairAssistantProperties;

/**
 * Resolves an authoritative manual projection back to its registered PDF.
 *
 * <p>The client supplies a database id, never a local path. The resolved file
 * must both exist in {@code source_file} and remain inside the configured
 * knowledge directory, which prevents the document endpoint from becoming an
 * arbitrary filesystem reader.
 */
@Service
public class ManualDocumentService {

    private final JdbcTemplate jdbcTemplate;
    private final Path knowledgeDirectory;

    public ManualDocumentService(
            JdbcTemplate jdbcTemplate,
            RepairAssistantProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.knowledgeDirectory = Path.of(properties.knowledge().sourcePath())
                .toAbsolutePath()
                .normalize();
    }

    public ManualDocument requireDocument(long manualKnowledgeId) {
        ManualDocument document = jdbcTemplate.query("""
                SELECT mp.id, sf.original_file_name
                FROM manual_knowledge_projection_v1 mp
                JOIN knowledge_unit_source kus
                  ON kus.knowledge_unit_version_id = mp.knowledge_unit_version_id
                 AND kus.relation_type = 'PRIMARY'
                JOIN source_record sr ON sr.id = kus.source_record_id
                JOIN source_file sf ON sf.id = sr.source_file_id
                WHERE mp.id = ?
                  AND sf.source_kind IN ('SERVICE_MANUAL', 'PARTS_MANUAL')
                  AND sf.status = 'VALIDATED'
                LIMIT 1
                """, resultSet -> resultSet.next()
                        ? new ManualDocument(
                                resultSet.getLong("id"),
                                resultSet.getString("original_file_name"),
                                resolveRegisteredFile(resultSet.getString("original_file_name")))
                        : null,
                manualKnowledgeId);
        if (document == null || !Files.isRegularFile(document.path())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Registered service manual PDF is unavailable");
        }
        return document;
    }

    private Path resolveRegisteredFile(String fileName) {
        Path resolved = knowledgeDirectory.resolve(fileName).normalize();
        if (!resolved.startsWith(knowledgeDirectory)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Registered service manual PDF is unavailable");
        }
        return resolved;
    }

    public record ManualDocument(long id, String fileName, Path path) {
    }
}
