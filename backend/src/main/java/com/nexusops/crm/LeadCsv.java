package com.nexusops.crm;

import com.nexusops.shared.web.ApiProblem;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * RFC 4180 CSV for lead import (D11): UTF-8 (a BOM is ignored), comma-separated, quoted fields with "" escapes and
 * embedded line breaks, \n or \r\n records. File-level problems are 400 field errors on "file"; row-level ones are
 * left to the importer. Cell text is only ever stored, never evaluated.
 */
final class LeadCsv {

    static final int MAX_BYTES = 256 * 1024;
    static final int MAX_ROWS = 500;
    static final List<String> COLUMNS = List.of("first_name", "last_name", "company", "job_title", "email", "phone",
            "source", "estimated_value", "currency", "description");

    /** {@code row} is the spreadsheet row number (the header is row 1). */
    record Row(int row, Map<String, String> values, boolean tooManyValues) {}

    private LeadCsv() {}

    static List<Row> parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            throw fileError("Use a CSV file of at most 256 KB.");
        }
        String text = decode(bytes);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<List<String>> records = records(text);
        int headerIndex = 0;
        while (headerIndex < records.size() && blank(records.get(headerIndex))) {
            headerIndex++;
        }
        if (headerIndex == records.size()) {
            throw fileError("The file is empty.");
        }
        List<String> header = trimTrailingBlanks(records.get(headerIndex).stream()
                .map(h -> h.strip().toLowerCase(Locale.ROOT).replace(' ', '_')).toList());
        checkHeader(header);
        List<Row> rows = new ArrayList<>();
        // Blank lines are skipped but still counted, so row numbers match the spreadsheet the user sees.
        for (int i = headerIndex + 1; i < records.size(); i++) {
            List<String> cells = records.get(i);
            if (blank(cells)) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int c = 0; c < Math.min(cells.size(), header.size()); c++) {
                values.put(header.get(c), cells.get(c));
            }
            // Spreadsheet exports often pad rows with empty cells; only extra cells holding text count.
            boolean tooMany = cells.size() > header.size()
                    && !blank(cells.subList(header.size(), cells.size()));
            rows.add(new Row(i + 1, values, tooMany));
        }
        if (rows.size() > MAX_ROWS) {
            throw fileError("Import at most " + MAX_ROWS + " leads at a time.");
        }
        return rows;
    }

    private static void checkHeader(List<String> header) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String column : header) {
            if (!column.isEmpty() && !seen.add(column)) {
                throw fileError("The column " + column + " appears more than once.");
            }
            if (!COLUMNS.contains(column)) {
                unknown.add(column.isEmpty() ? "(blank)" : column);
            }
        }
        if (!unknown.isEmpty()) {
            throw fileError("Unknown columns: " + String.join(", ", unknown) + ". Use: " + String.join(", ", COLUMNS) + ".");
        }
        if (!seen.contains("first_name") && !seen.contains("last_name") && !seen.contains("company")) {
            throw fileError("Add a first_name, last_name or company column.");
        }
    }

    /** Splits into records of fields. Line breaks inside quotes belong to the field. */
    private static List<List<String>> records(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            any = true;
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"' && field.isEmpty()) {
                quoted = true;
            } else if (ch == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                fields.add(field.toString());
                field.setLength(0);
                records.add(fields);
                fields = new ArrayList<>();
                any = false;
            } else {
                field.append(ch);
            }
        }
        if (quoted) {
            throw fileError("A quoted value is not closed.");
        }
        if (any) {
            fields.add(field.toString());
            records.add(fields);
        }
        return records;
    }

    /** Excel and Sheets pad exports with empty trailing header cells; those are not columns. */
    private static List<String> trimTrailingBlanks(List<String> header) {
        int end = header.size();
        while (end > 0 && header.get(end - 1).isEmpty()) {
            end--;
        }
        return header.subList(0, end);
    }

    private static boolean blank(List<String> record) {
        return record.stream().allMatch(String::isBlank);
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw fileError("Save the file as UTF-8 CSV and try again.");
        }
    }

    private static ApiProblem fileError(String message) {
        return ApiProblem.badRequestField("file", message);
    }
}
