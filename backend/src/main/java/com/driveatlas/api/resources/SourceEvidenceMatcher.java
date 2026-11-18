package com.driveatlas.api.resources;

import java.util.Arrays;
import org.springframework.stereotype.Component;

@Component
public class SourceEvidenceMatcher {
    public String findExactQuote(String sourceText, String evidence) {
        NormalizedText normalizedEvidence = normalizeWithOffsets(evidence);
        if (normalizedEvidence.value().isEmpty()) return null;
        NormalizedText normalizedSource = normalizeWithOffsets(sourceText);
        int matchStart = normalizedSource.value().indexOf(normalizedEvidence.value());
        if (matchStart < 0) return null;

        int sourceStart = normalizedSource.starts()[matchStart];
        int sourceEnd = normalizedSource.ends()[matchStart + normalizedEvidence.value().length() - 1];
        return sourceText.substring(sourceStart, sourceEnd);
    }

    public String normalizeWhitespace(String value) {
        return normalizeWithOffsets(value).value();
    }

    private NormalizedText normalizeWithOffsets(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        int[] starts = new int[value.length()];
        int[] ends = new int[value.length()];
        int length = 0;
        for (int index = 0; index < value.length(); index += 1) {
            char current = value.charAt(index);
            if (Character.isWhitespace(current) || Character.isSpaceChar(current)) {
                if (length > 0) {
                    if (normalized.charAt(length - 1) == ' ') {
                        ends[length - 1] = index + 1;
                    } else {
                        normalized.append(' ');
                        starts[length] = index;
                        ends[length] = index + 1;
                        length += 1;
                    }
                }
            } else {
                normalized.append(current);
                starts[length] = index;
                ends[length] = index + 1;
                length += 1;
            }
        }
        if (length > 0 && normalized.charAt(length - 1) == ' ') length -= 1;
        return new NormalizedText(
                normalized.substring(0, length),
                Arrays.copyOf(starts, length),
                Arrays.copyOf(ends, length));
    }

    private record NormalizedText(String value, int[] starts, int[] ends) {
    }
}
