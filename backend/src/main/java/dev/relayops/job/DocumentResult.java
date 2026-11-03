package dev.relayops.job;

import java.util.List;

public record DocumentResult(String schemaVersion, String title, Claim summary, List<Claim> keyPoints,
                             List<Claim> constraints, List<String> unknowns, String modelProfile,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Review review,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) VersionDiff diff) {
    public DocumentResult(String schemaVersion, String title, Claim summary, List<Claim> keyPoints,
                          List<Claim> constraints, List<String> unknowns, String modelProfile) {
        this(schemaVersion,title,summary,keyPoints,constraints,unknowns,modelProfile,null,null);
    }
    public DocumentResult(String schemaVersion, String title, Claim summary, List<Claim> keyPoints,
                          List<Claim> constraints, List<String> unknowns, String modelProfile, Review review) {
        this(schemaVersion,title,summary,keyPoints,constraints,unknowns,modelProfile,review,null);
    }
    public record Evidence(String paragraphId, String quote) {}
    public record Claim(String text, List<Evidence> evidence) {}
    public record Finding(String kind, String text, String question, List<Evidence> evidence) {}
    public record Criterion(String title, String given, String when, String then, List<Evidence> evidence, List<String> assumptions) {}
    public record Review(List<Finding> findings, List<Criterion> acceptanceCriteria) {}
    public record Change(String kind, String text, List<Evidence> before, List<Evidence> after) {}
    public record VersionDiff(List<Change> changes, List<Claim> impactedConstraints, List<Criterion> impactedAcceptanceCriteria) {}
    public static void validate(DocumentResult result, List<DocumentInput.Paragraph> paragraphs) {
        if (result == null || !(TaskType.EXTRACT.schemaVersion.equals(result.schemaVersion()) || TaskType.REQUIREMENTS_REVIEW.schemaVersion.equals(result.schemaVersion()) || TaskType.VERSION_DIFF.schemaVersion.equals(result.schemaVersion()))
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
            if (result.review() != null || result.diff() != null) throw new IllegalStateException("OUTPUT_INVALID");
            return;
        }
        if (TaskType.REQUIREMENTS_REVIEW.schemaVersion.equals(result.schemaVersion())) {
            if (result.diff() != null) invalid();
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
            for (Criterion criterion : review.acceptanceCriteria()) criterion(criterion,paragraphs);
            return;
        }
        if (result.review() != null) invalid();
        VersionDiff diff = result.diff();
        if (diff == null || diff.changes() == null || diff.impactedConstraints() == null || diff.impactedAcceptanceCriteria() == null
                || diff.changes().size() > 8 || diff.impactedConstraints().size() > 6 || diff.impactedAcceptanceCriteria().size() > 6
                || !result.keyPoints().isEmpty() || !result.constraints().isEmpty() || !result.unknowns().isEmpty()) invalid();
        for (Change change : diff.changes()) {
            if (change == null || change.kind() == null || !List.of("ADDED","REMOVED","MODIFIED").contains(change.kind())
                    || !text(change.text(),1000) || change.before() == null || change.after() == null) invalid();
            if (change.kind().equals("ADDED")) {
                if (!change.before().isEmpty()) invalid();
                evidence(change.after(),paragraphs);
            } else if (change.kind().equals("REMOVED")) {
                if (!change.after().isEmpty()) invalid();
                evidence(change.before(),paragraphs);
            } else {
                evidence(change.before(),paragraphs);
                evidence(change.after(),paragraphs);
            }
        }
        for (Claim claim : diff.impactedConstraints()) claim(claim,paragraphs);
        for (Criterion criterion : diff.impactedAcceptanceCriteria()) criterion(criterion,paragraphs);
    }
    private static void claim(Claim claim,List<DocumentInput.Paragraph> paragraphs) {
        if (claim == null || !text(claim.text(),2000)) invalid();
        evidence(claim.evidence(),paragraphs);
    }
    private static void criterion(Criterion criterion,List<DocumentInput.Paragraph> paragraphs) {
        if (criterion == null || !text(criterion.title(),200) || !text(criterion.given(),1000)
                || !text(criterion.when(),1000) || !text(criterion.then(),1000) || criterion.assumptions() == null
                || criterion.assumptions().size() > 5 || criterion.assumptions().stream().anyMatch(s -> !text(s,500))) invalid();
        evidence(criterion.evidence(),paragraphs);
    }
    private static boolean text(String value,int limit) { return value != null && !value.isBlank() && value.length() <= limit; }
    private static void invalid() { throw new IllegalStateException("OUTPUT_INVALID"); }
    private static void evidence(List<Evidence> values,List<DocumentInput.Paragraph> paragraphs) {
        if (values == null || values.isEmpty() || values.size() > 10) invalid();
        for (Evidence e : values) if (e == null || !text(e.quote(),1000) || paragraphs.stream().noneMatch(p -> p.paragraphId().equals(e.paragraphId()) && p.text().contains(e.quote())))
            throw new IllegalStateException("EVIDENCE_INVALID");
    }
}
