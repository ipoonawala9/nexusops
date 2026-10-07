package com.nexusops.collaboration.web;

import com.nexusops.collaboration.DocumentContent;
import com.nexusops.collaboration.DocumentService;
import com.nexusops.collaboration.DocumentView;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/documents")
class DocumentController {

    private final DocumentService documents;

    DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('collaboration.document.read')")
    List<DocumentView> list(@RequestParam(required = false) String subjectType,
            @RequestParam(required = false) UUID subjectId) {
        return documents.list(subjectType, subjectId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('collaboration.document.manage')")
    DocumentView upload(@RequestParam(required = false) String subjectType,
            @RequestParam(required = false) UUID subjectId,
            @RequestPart(name = "file", required = false) MultipartFile file) {
        return documents.upload(subjectType, subjectId, file);
    }

    /** Always a download (attachment + nosniff), never rendered by the browser, whatever the stored type. */
    @GetMapping("/{id}/content")
    @PreAuthorize("hasAuthority('collaboration.document.read')")
    ResponseEntity<byte[]> content(@PathVariable UUID id) {
        DocumentContent content = documents.content(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(content.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(content.bytes());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('collaboration.document.manage')")
    void delete(@PathVariable UUID id) {
        documents.delete(id);
    }
}
