package com.aifieldservice.repairassistant.api;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aifieldservice.repairassistant.knowledge.ManualDocumentService;
import com.aifieldservice.repairassistant.knowledge.ManualDocumentService.ManualDocument;

/** HTTP boundary for viewing the immutable source PDF behind manual evidence. */
@RestController
@RequestMapping("/api/v1/knowledge/manuals")
public class ManualDocumentController {

    private final ManualDocumentService documentService;

    public ManualDocumentController(ManualDocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * Returns a filesystem Resource rather than loading the complete PDF into memory.
     * Spring MVC can therefore honor HTTP Range requests used by PDF.js.
     */
    @GetMapping(value = "/{manualKnowledgeId}/document", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<Resource> getDocument(@PathVariable long manualKnowledgeId) {
        ManualDocument document = documentService.requireDocument(manualKnowledgeId);
        Resource resource = new FileSystemResource(document.path());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(document.path().toFile().length())
                .cacheControl(CacheControl.noCache())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(document.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(resource);
    }
}
