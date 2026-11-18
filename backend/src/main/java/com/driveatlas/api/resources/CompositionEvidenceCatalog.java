package com.driveatlas.api.resources;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CompositionEvidenceCatalog {
    private static final int MAX_EXCERPT_LENGTH = 500;

    public Catalog create(List<CompositionSource> sources) {
        List<SourceDocument> documents = new ArrayList<>();
        Map<String, Excerpt> excerptsById = new HashMap<>();
        int excerptCount = 0;

        for (CompositionSource source : sources) {
            List<Excerpt> excerpts = new ArrayList<>();
            BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
            sentences.setText(source.text());
            int start = sentences.first();
            for (int end = sentences.next(); end != BreakIterator.DONE; start = end, end = sentences.next()) {
                excerptCount = addExcerpts(source, start, end, excerpts, excerptsById, excerptCount);
            }
            documents.add(new SourceDocument(
                    source.sourceId(), source.name(), source.mimeType(), List.copyOf(excerpts)));
        }

        return new Catalog(List.copyOf(documents), Map.copyOf(excerptsById));
    }

    private int addExcerpts(
            CompositionSource source,
            int start,
            int end,
            List<Excerpt> excerpts,
            Map<String, Excerpt> excerptsById,
            int excerptCount) {
        String text = source.text();
        int chunkStart = start;
        while (chunkStart < end) {
            while (chunkStart < end && isWhitespace(text.charAt(chunkStart))) chunkStart += 1;
            if (chunkStart >= end) break;

            int chunkEnd = Math.min(end, chunkStart + MAX_EXCERPT_LENGTH);
            if (chunkEnd < end) {
                int boundary = chunkEnd;
                while (boundary > chunkStart && !isWhitespace(text.charAt(boundary - 1))) boundary -= 1;
                if (boundary > chunkStart) chunkEnd = boundary;
            }
            while (chunkEnd > chunkStart && isWhitespace(text.charAt(chunkEnd - 1))) chunkEnd -= 1;

            String quote = text.substring(chunkStart, chunkEnd);
            String evidenceId = "E" + (excerptCount + 1);
            Excerpt excerpt = new Excerpt(evidenceId, source.sourceId(), quote);
            excerpts.add(excerpt);
            excerptsById.put(evidenceId, excerpt);
            excerptCount += 1;
            chunkStart = Math.max(chunkStart + 1, chunkEnd);
        }
        return excerptCount;
    }

    private static boolean isWhitespace(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value);
    }

    public record Catalog(List<SourceDocument> sources, Map<String, Excerpt> excerptsById) {
    }

    public record SourceDocument(String sourceId, String name, String mimeType, List<Excerpt> excerpts) {
    }

    public record Excerpt(String evidenceId, String sourceId, String quote) {
    }
}
