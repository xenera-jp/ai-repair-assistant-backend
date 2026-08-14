package com.aifieldservice.repairassistant.service.knowledge.impl;

import com.aifieldservice.repairassistant.service.knowledge.*;
import com.aifieldservice.repairassistant.service.knowledge.ManualDocumentService.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.aifieldservice.repairassistant.config.RepairAssistantProperties;
import com.aifieldservice.repairassistant.dao.knowledge.ManualDocumentMapper;
import com.aifieldservice.repairassistant.domain.knowledge.model.ManualDocumentRecord;

/**
 * Resolves an authoritative manual projection back to its registered PDF.
 *
 * <p>The client supplies a database id, never a local path. The resolved file
 * must both exist in {@code source_file} and remain inside the configured
 * knowledge directory, which prevents the document endpoint from becoming an
 * arbitrary filesystem reader.
 */
@Service
public class ManualDocumentServiceImpl implements ManualDocumentService {

    private final ManualDocumentMapper manualDocumentMapper;
    private final Path knowledgeDirectory;

    public ManualDocumentServiceImpl(
            ManualDocumentMapper manualDocumentMapper,
            RepairAssistantProperties properties) {
        this.manualDocumentMapper = manualDocumentMapper;
        this.knowledgeDirectory = Path.of(properties.knowledge().sourcePath())
                .toAbsolutePath()
                .normalize();
    }

    @Transactional(readOnly = true)
    public ManualDocument requireDocument(long manualKnowledgeId) {
        ManualDocumentRecord row = manualDocumentMapper.findRegisteredDocument(manualKnowledgeId);
        ManualDocument document = row == null ? null : new ManualDocument(
                row.id(), row.originalFileName(), resolveRegisteredFile(row.originalFileName()));
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

}
