package com.nexusops.helpdesk;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Free text → a safe {@code to_tsquery('simple', …)} string: lower-case words of letters, combining marks and digits
 * (any script), at least 2 characters, distinct, at most {@code maxTerms}, OR-ed together. Operators and punctuation
 * can't survive the split, so user text never becomes tsquery syntax. Empty when nothing usable remains.
 */
final class FullTextTerms {

    private FullTextTerms() {}

    static String orQuery(String text, int maxTerms) {
        if (text == null) {
            return "";
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{M}\\p{N}]+"))
                .filter(word -> word.codePointCount(0, word.length()) >= 2)
                .distinct()
                .limit(maxTerms)
                .collect(Collectors.joining(" | "));
    }
}
