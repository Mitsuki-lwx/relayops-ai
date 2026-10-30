package dev.relayops.job;

import java.util.List;

public record DocumentResult(String schemaVersion, String title, Claim summary, List<Claim> keyPoints,
                             List<Claim> constraints, List<String> unknowns, String modelProfile) {
    public record Evidence(String paragraphId, String quote) {}
    public record Claim(String text, List<Evidence> evidence) {}
    public static void validate(DocumentResult result, List<DocumentInput.Paragraph> paragraphs) {
        if (result == null || !"document.extract.result.v1".equals(result.schemaVersion())
                || result.title() == null || result.title().length() > 100 || result.modelProfile() == null || result.summary() == null
                || result.keyPoints() == null || result.constraints() == null || result.unknowns() == null
                || result.keyPoints().size() > 10 || result.constraints().size() > 10 || result.unknowns().size() > 10)
            throw new IllegalStateException("OUTPUT_INVALID");
        var all = new java.util.ArrayList<>(result.keyPoints());
        all.add(result.summary()); all.addAll(result.constraints());
        for (Claim claim : all) {
            if (claim == null || claim.text() == null || claim.text().isBlank() || claim.text().length() > 2000
                    || claim.evidence() == null || claim.evidence().isEmpty() || claim.evidence().size() > 10)
                throw new IllegalStateException("OUTPUT_INVALID");
            for (Evidence evidence : claim.evidence()) {
                if (evidence == null || evidence.paragraphId() == null || evidence.quote() == null || evidence.quote().isBlank() || evidence.quote().length() > 1000
                        || paragraphs.stream().noneMatch(p -> p.paragraphId().equals(evidence.paragraphId()) && p.text().contains(evidence.quote())))
                    throw new IllegalStateException("EVIDENCE_INVALID");
            }
        }
        if (result.unknowns().stream().anyMatch(s -> s == null || s.length() > 1000)) throw new IllegalStateException("OUTPUT_INVALID");
    }
}
