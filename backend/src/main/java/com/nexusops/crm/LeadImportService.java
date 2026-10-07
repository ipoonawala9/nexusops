package com.nexusops.crm;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.crm.domain.Lead;
import com.nexusops.crm.domain.LeadDetails;
import com.nexusops.crm.domain.LeadRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** D11: every row is validated like a hand-made lead; one bad row stores nothing. */
@Service
public class LeadImportService {

    public record RowError(int row, String field, String message) {}

    static final int MAX_REPORTED = 50;
    /** Service field names → CSV column names, so errors point at the user's columns. */
    private static final Map<String, String> COLUMN_OF = Map.of("firstName", "first_name", "lastName", "last_name",
            "companyName", "company", "jobTitle", "job_title", "estimatedValue", "estimated_value");

    private final LeadService leads;
    private final LeadRepository rows;
    private final AuditService audit;

    LeadImportService(LeadService leads, LeadRepository rows, AuditService audit) {
        this.leads = leads;
        this.rows = rows;
        this.audit = audit;
    }

    @Transactional
    public ImportResult importLeads(MultipartFile file) {
        TenantContext.requireTenantId();
        if (file == null || file.isEmpty()) {
            throw ApiProblem.badRequestField("file", "Choose a CSV file.");
        }
        if (file.getSize() > LeadCsv.MAX_BYTES) {
            throw ApiProblem.badRequestField("file", "Use a CSV file of at most 256 KB.");
        }
        List<LeadCsv.Row> parsed;
        try {
            parsed = LeadCsv.parse(file.getBytes());
        } catch (IOException unreadable) {
            throw ApiProblem.badRequestField("file", "The file could not be read.");
        }
        if (parsed.isEmpty()) {
            throw ApiProblem.badRequestField("file", "The file has no leads.");
        }
        List<RowError> errors = new ArrayList<>();
        List<LeadDetails> valid = new ArrayList<>();
        for (LeadCsv.Row row : parsed) {
            if (row.tooManyValues()) {
                errors.add(new RowError(row.row(), "row", "This row has more values than the header."));
                continue;
            }
            try {
                valid.add(leads.validate(command(row.values()), null));
            } catch (ApiProblem problem) {
                problem.errors().forEach(e -> errors.add(new RowError(row.row(),
                        COLUMN_OF.getOrDefault(e.field(), e.field()), e.message())));
            }
        }
        if (!errors.isEmpty()) {
            throw ApiProblem.unprocessable("Some rows can't be imported. Fix them and upload the file again.")
                    .withProperty("rows", errors.subList(0, Math.min(MAX_REPORTED, errors.size())))
                    .withProperty("errorCount", errors.size());
        }
        UUID me = LeadService.currentUser();
        rows.saveAll(valid.stream().map(d -> new Lead(Ids.newId(), LeadService.withOwner(d, me), me)).toList());
        rows.flush();
        audit.record(AuditEntry.of("LeadsImported", "Lead", null).withMetadata(Map.of("count", valid.size())));
        return new ImportResult(valid.size());
    }

    private static LeadCommand command(Map<String, String> v) {
        return new LeadCommand(v.get("first_name"), v.get("last_name"), v.get("company"), v.get("job_title"),
                v.get("email"), v.get("phone"), source(v.get("source")), null, amount(v.get("estimated_value")),
                v.get("currency"), v.get("description"));
    }

    private static LeadSource source(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LeadSource.valueOf(raw.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            throw ApiProblem.badRequestField("source",
                    "Use WEBSITE, REFERRAL, WALK_IN, PHONE, EMAIL, SOCIAL, EVENT or OTHER.");
        }
    }

    private static BigDecimal amount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.strip());
        } catch (NumberFormatException invalid) {
            throw ApiProblem.badRequestField("estimatedValue", "Enter a number like 1200.50.");
        }
    }
}
