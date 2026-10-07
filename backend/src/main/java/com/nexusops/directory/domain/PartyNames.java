package com.nexusops.directory.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Display names and the match keys that duplicate detection compares (ADR-0008). */
public final class PartyNames {

    private static final int MAX_KEY = 200;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Set<String> LEGAL_SUFFIXES = Set.of("ab", "ag", "as", "bv", "co", "company", "corp",
            "corporation", "gmbh", "inc", "incorporated", "kg", "limited", "llc", "llp", "lp", "ltd", "nv", "oy", "plc",
            "pte", "pty", "pvt", "sa", "sarl", "sas", "spa", "srl");

    private PartyNames() {}

    public static String fullName(String firstName, String lastName) {
        return lastName == null ? firstName : firstName + " " + lastName;
    }

    /** Ignores case, accents, punctuation and spacing: "Ada  LOVELACE" and "ada lovelace", "Seán O’Brien" and "Sean O'Brien" share a key. */
    public static String personKey(String firstName, String lastName) {
        String name = fullName(firstName, lastName);
        String folded = MARKS.matcher(Normalizer.normalize(name, Normalizer.Form.NFKD)).replaceAll("")
                .toLowerCase(Locale.ROOT);
        String key = String.join(" ", Arrays.stream(NON_ALPHANUMERIC.split(folded)).filter(t -> !t.isEmpty()).toList());
        return limit(key.isEmpty() ? WHITESPACE.matcher(name.strip()).replaceAll(" ").toLowerCase(Locale.ROOT) : key);
    }

    /** Ignores case, accents, punctuation, spacing and trailing legal suffixes: "ACME, Inc." and "Acme" share a key. */
    public static String organizationKey(String name) {
        String folded = MARKS.matcher(Normalizer.normalize(name, Normalizer.Form.NFKD)).replaceAll("")
                .toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>(Arrays.stream(NON_ALPHANUMERIC.split(folded)).filter(t -> !t.isEmpty()).toList());
        while (tokens.size() > 1 && LEGAL_SUFFIXES.contains(tokens.getLast())) {
            tokens.removeLast();
        }
        String key = String.join("", tokens);
        return key.isEmpty() ? personKey(name, null) : limit(key);
    }

    private static String limit(String key) {
        return key.length() > MAX_KEY ? key.substring(0, MAX_KEY) : key;
    }
}
