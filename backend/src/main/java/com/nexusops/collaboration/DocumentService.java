package com.nexusops.collaboration;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.domain.Document;
import com.nexusops.collaboration.domain.DocumentRepository;
import com.nexusops.identity.Members;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Files attached to subject records (D10). Each needs the subject's read permission; uploads need it writable. */
@Service
public class DocumentService {

    static final long MAX_BYTES = 10L * 1024 * 1024;
    static final String NOT_FOUND = "Record not found.";

    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final Subjects subjects;
    private final Members members;
    private final TenantDirectory tenants;
    private final TenantLocks locks;
    private final AuditService audit;

    DocumentService(DocumentRepository documents, DocumentStorage storage, Subjects subjects, Members members,
            TenantDirectory tenants, TenantLocks locks, AuditService audit) {
        this.documents = documents;
        this.storage = storage;
        this.subjects = subjects;
        this.members = members;
        this.tenants = tenants;
        this.locks = locks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<DocumentView> list(String subjectType, UUID subjectId) {
        SubjectRef subject = subjects.requireReadable(subjectType, subjectId);
        List<Document> found = documents.findBySubjectTypeAndSubjectIdOrderByCreatedAtDesc(subject.type(), subject.id());
        Map<UUID, Members.Member> uploaders = members.findAll(
                found.stream().map(Document::getUploadedBy).filter(Objects::nonNull).toList());
        return found.stream().map(d -> view(d, uploaders)).toList();
    }

    @Transactional
    public DocumentView upload(String subjectType, UUID subjectId, MultipartFile file) {
        SubjectRef subject = subjects.requireWritable(subjectType, subjectId);
        if (file == null) {
            throw ApiProblem.badRequestField("file", "Choose a file to upload.");
        }
        if (file.isEmpty()) {
            throw ApiProblem.badRequestField("file", "The file is empty.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiProblem.payloadTooLarge("The file is larger than 10 MB.");
        }
        byte[] bytes = bytes(file);
        locks.lock("documents");
        Integer maxMb = tenants.currentLimits().maxStorageMb();
        if (maxMb != null && documents.totalBytes() + bytes.length > maxMb * 1024L * 1024L) {
            throw ApiProblem.conflict("Your plan allows " + maxMb + " MB of documents. Delete some or upgrade to add more.");
        }
        UUID uploader = TenantContext.userId().orElseThrow(() -> new IllegalStateException("No user bound"));
        Document document = new Document(Ids.newId(), subject.type(), subject.id(),
                FileNames.sanitize(file.getOriginalFilename()), FileNames.contentType(file.getContentType()),
                bytes.length, sha256(bytes), uploader);
        documents.saveAndFlush(document);
        storage.store(document.getId(), bytes);
        audit.record(AuditEntry.of("DocumentUploaded", "Document", document.getId()).withMetadata(Map.of(
                "subjectType", subject.type(), "subjectId", subject.id().toString(),
                "fileName", document.getFileName(), "sizeBytes", document.getSizeBytes(),
                "sha256", document.getSha256())));
        return view(document, members.findAll(List.of(uploader)));
    }

    @Transactional(readOnly = true)
    public DocumentContent content(UUID id) {
        Document document = find(id);
        subjects.requireReadable(document.getSubjectType(), document.getSubjectId());
        byte[] bytes = storage.load(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
        return new DocumentContent(document.getFileName(), document.getContentType(), bytes);
    }

    @Transactional
    public void delete(UUID id) {
        Document document = find(id);
        subjects.requireReadable(document.getSubjectType(), document.getSubjectId());
        storage.delete(id);
        documents.delete(document);
        documents.flush();
        audit.record(AuditEntry.of("DocumentDeleted", "Document", id).withBefore(Map.of(
                "fileName", document.getFileName(), "sizeBytes", document.getSizeBytes())));
    }

    private Document find(UUID id) {
        TenantContext.requireTenantId();
        return documents.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
    }

    private static DocumentView view(Document d, Map<UUID, Members.Member> uploaders) {
        Members.Member uploader = d.getUploadedBy() == null ? null : uploaders.get(d.getUploadedBy());
        return new DocumentView(d.getId(), d.getSubjectType(), d.getSubjectId(), d.getFileName(), d.getContentType(),
                d.getSizeBytes(), d.getSha256(), uploader == null ? null : new MemberRef(uploader.id(), uploader.name()),
                d.getCreatedAt());
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
