package dev.relayops.job;

import java.util.List;

public record DocumentResult(String schemaVersion, String title, Claim summary, List<Claim> keyPoints,
                             List<Claim> constraints, List<String> unknowns, String modelProfile,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Review review) {
    public DocumentResult(String schemaVersion, String title, Claim summary, List<Claim> keyPoints,
                          List<Claim> constraints, List<String> unknowns, String modelProfile) {
        this(schemaVersion,title,summary,keyPoints,constraints,unknowns,modelProfile,null);
    }
    public record Evidence(String paragraphId, String quote) {}
    public record Claim(String text, List<Evidence> evidence) {}
    public record Finding(String kind, String text, String question, List<Evidence> evidence) {}
    public record Criterion(String title, String given, String when, String then, List<Evidence> evidence, List<String> assumptions) {}
    public record Review(List<Finding> findings, List<Criterion> acceptanceCriteria) {}
    public static void validate(DocumentResult result, List<DocumentInput.Paragraph> paragraphs) {
        if (result == null || !(TaskType.EXTRACT.schemaVersion.equals(result.schemaVersion()) || TaskType.REQUIREMENTS_REVIEW.schemaVersion.equals(result.schemaVersion()))
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
        if (TaskType.EXTRACT.schemaVersion.equals(result.schemaVersion())) {
            if (result.review() != null) throw new IllegalStateException("OUTPUT_INVALID");
            return;
        }
        Review review = result.review();
        if (review == null || review.findings() == null || review.acceptanceCriteria() == null
                || review.findings().size() > 6 || review.acceptanceCriteria().size() > 6
                || !result.keyPoints().isEmpty() || !result.constraints().isEmpty() || !result.unknowns().isEmpty()) invalid();
        for (Finding finding : review.findings()) {
            if (finding == null || finding.kind() == null || !List.of("GAP","AMBIGUITY","CONFLICT").contains(finding.kind())
                    || !text(finding.text(),1000) || !text(finding.question(),500)) invalid();
            evidence(finding.evidence(),paragraphs);
            if (finding.kind().equals("CONFLICT") && finding.evidence().stream().distinct().count() < 2) invalid();
        }
        for (Criterion criterion : review.acceptanceCriteria()) {
            if (criterion == null || !text(criterion.title(),200) || !text(criterion.given(),1000)
                    || !text(criterion.when(),1000) || !text(criterion.then(),1000) || criterion.assumptions() == null
                    || criterion.assumptions().size() > 5 || criterion.assumptions().stream().anyMatch(s -> !text(s,500))) invalid();
            evidence(criterion.evidence(),paragraphs);
        }
    }
    private static boolean text(String value,int limit) { return value != null && !value.isBlank() && value.length() <= limit; }
    private static void invalid() { throw new IllegalStateException("OUTPUT_INVALID"); }
    private static void evidence(List<Evidence> values,List<DocumentInput.Paragraph> paragraphs) {
        if (values == null || values.isEmpty() || values.size() > 10) invalid();
        for (Evidence e : values) if (e == null || !text(e.quote(),1000) || paragraphs.stream().noneMatch(p -> p.paragraphId().equals(e.paragraphId()) && p.text().contains(e.quote())))
            throw new IllegalStateException("EVIDENCE_INVALID");
    }
}
