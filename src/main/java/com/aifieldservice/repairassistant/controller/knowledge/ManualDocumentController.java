package com.aifieldservice.repairassistant.controller.knowledge;

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

import com.aifieldservice.repairassistant.service.knowledge.ManualDocumentService;
import com.aifieldservice.repairassistant.service.knowledge.ManualDocumentService.ManualDocument;

/** HTTP boundary for viewing the immutable source PDF behind manual evidence. */
@RestController
@RequestMapping("/api/v1/knowledge/manuals")
public class ManualDocumentController {

    private final ManualDocumentService documentService;

    public ManualDocumentController(ManualDocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * 按手册知识标识在线返回原始 PDF，支持 PDF.js 所需的 HTTP Range 分段请求，避免整份文件载入内存。
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
