package com.driveatlas.api.resources;

import java.util.List;

public record CompositionResult(String title, List<Section> sections, List<MergeNote> notes) {
    public record Section(String heading, List<Paragraph> paragraphs) {
    }

    public record Paragraph(String text, List<Evidence> evidence) {
    }

    public record MergeNote(String category, String summary, List<Evidence> evidence) {
    }

    public record Evidence(String sourceId, String quote) {
    }
}
