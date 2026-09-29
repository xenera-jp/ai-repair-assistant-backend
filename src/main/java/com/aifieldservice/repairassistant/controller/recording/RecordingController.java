package com.aifieldservice.repairassistant.controller.recording;

import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.aifieldservice.repairassistant.domain.recording.model.RecordingViews;
import com.aifieldservice.repairassistant.service.recording.RecordingService;
import com.aifieldservice.repairassistant.service.recording.RecordingService.CorrectionDecision;

@RestController
@RequestMapping("/api/v1")
public class RecordingController {
    public record RoleRequest(String roleCode) {}
    public record IssueCreateRequest(String type, String content) {}
    public record IssueUpdateRequest(String content, int version) {}
    public record LinkRequest(String id) {}
    public record CorrectionConfirmationRequest(List<CorrectionDecision> decisions) {}

    private final RecordingService service;
    public RecordingController(RecordingService service) { this.service = service; }

    @PostMapping(value="/recording-batches", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public RecordingViews.Batch create(@RequestParam("files") List<MultipartFile> files,
            @RequestParam(defaultValue="AUTO") String language) { return service.create(files, language); }

    @GetMapping("/recording-batches/{batchId}")
    public RecordingViews.Batch batch(@PathVariable String batchId) { return service.getBatch(batchId); }

    @PostMapping("/recording-files/{fileId}/transcription-retries")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RecordingViews.Batch retryFile(@PathVariable String fileId) { return service.retryTranscription(fileId); }

    @PostMapping("/recording-batches/{batchId}/issue-extractions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RecordingViews.Batch retryExtraction(@PathVariable String batchId) { return service.retryExtraction(batchId); }

    @PutMapping("/recording-files/{fileId}/speakers/{speakerLabel}/role")
    public RecordingViews.Batch role(@PathVariable String fileId, @PathVariable String speakerLabel,
            @RequestBody RoleRequest request) { return service.setSpeakerRole(fileId, speakerLabel, request.roleCode()); }

    @PostMapping("/recording-batches/{batchId}/extracted-issues")
    @ResponseStatus(HttpStatus.CREATED)
    public RecordingViews.Batch createIssue(@PathVariable String batchId,
            @RequestBody IssueCreateRequest request) { return service.createIssue(batchId, request.type(), request.content()); }

    @PatchMapping("/recording-batches/{batchId}/extracted-issues/{issueId}")
    public RecordingViews.Batch updateIssue(@PathVariable String batchId, @PathVariable String issueId,
            @RequestBody IssueUpdateRequest request) { return service.updateIssue(batchId, issueId, request.content(), request.version()); }

    @DeleteMapping("/recording-batches/{batchId}/extracted-issues/{issueId}")
    public RecordingViews.Batch deleteIssue(@PathVariable String batchId, @PathVariable String issueId) { return service.setIssueDeleted(batchId, issueId, true); }

    @PostMapping("/recording-batches/{batchId}/extracted-issues/{issueId}/restore")
    public RecordingViews.Batch restoreIssue(@PathVariable String batchId, @PathVariable String issueId) { return service.setIssueDeleted(batchId, issueId, false); }

    @PostMapping("/recording-batches/{batchId}/identifier-corrections/confirm")
    public RecordingViews.Batch confirmCorrections(@PathVariable String batchId,
            @RequestBody CorrectionConfirmationRequest request) {
        return service.confirmCorrections(batchId, request.decisions());
    }

    @DeleteMapping("/recording-files/{fileId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFile(@PathVariable String fileId) { service.deleteFile(fileId); }

    @PostMapping("/recording-batches/{batchId}/applications") @ResponseStatus(HttpStatus.CREATED)
    public RecordingViews.Application createApplication(@PathVariable String batchId) { return service.createApplication(batchId); }

    @GetMapping("/recording-applications/{applicationId}")
    public RecordingViews.Application application(@PathVariable String applicationId) { return service.getApplication(applicationId); }

    @PostMapping("/recording-applications/{applicationId}/consume")
    public RecordingViews.Application consume(@PathVariable String applicationId) { return service.consumeApplication(applicationId); }

    @PutMapping("/recording-applications/{applicationId}/problem-understanding")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void attachUnderstanding(@PathVariable String applicationId, @RequestBody LinkRequest request) { service.attachUnderstanding(applicationId, request.id()); }

    @PutMapping("/recording-applications/{applicationId}/diagnosis-session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void attachDiagnosis(@PathVariable String applicationId, @RequestBody LinkRequest request) { service.attachDiagnosis(applicationId, request.id()); }

    @GetMapping("/recording-files/{fileId}/content")
    public ResponseEntity<StreamingResponseBody> content(@PathVariable String fileId,
            @RequestParam(required=false) String ignored,
            @org.springframework.web.bind.annotation.RequestHeader(value=HttpHeaders.RANGE, required=false) String rangeHeader) throws Exception {
        var file = service.getFile(fileId); Path path = service.resolveContent(file);
        long length = java.nio.file.Files.size(path);
        MediaType type;
        try { type = MediaType.parseMediaType(file.contentType()); } catch (Exception e) { type = MediaType.APPLICATION_OCTET_STREAM; }
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(type); headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        long start = 0;
        long end = length - 1;
        HttpStatus status = HttpStatus.OK;
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            HttpRange range = HttpRange.parseRanges(rangeHeader).getFirst();
            start = range.getRangeStart(length);
            end = range.getRangeEnd(length);
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
            status = HttpStatus.PARTIAL_CONTENT;
        }
        long responseStart = start;
        long responseLength = end - start + 1;
        headers.setContentLength(responseLength);
        StreamingResponseBody body = output -> {
            try (RandomAccessFile input = new RandomAccessFile(path.toFile(), "r")) {
                input.seek(responseStart);
                byte[] buffer = new byte[8192];
                long remaining = responseLength;
                while (remaining > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) break;
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
            }
        };
        return new ResponseEntity<>(body, headers, status);
    }
}
