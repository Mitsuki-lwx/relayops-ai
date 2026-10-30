package dev.relayops.job;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class FakeModel {
    private final AtomicLong calls = new AtomicLong();
    public record Evidence(String paragraphId, String quote) {}
    public record Claim(String text, List<Evidence> evidence) {}
    public record Result(String schemaVersion, String title, Claim summary, List<Claim> keyPoints,
                         List<Claim> constraints, List<String> unknowns, String modelProfile) {}

    public Result extract(String title, List<DocumentInput.Paragraph> paragraphs, String language) {
        calls.incrementAndGet();
        List<Claim> claims = paragraphs.stream().limit(5).map(p -> {
            int end = p.text().offsetByCodePoints(0, Math.min(240, p.text().codePointCount(0, p.text().length())));
            String quote = p.text().substring(0, end);
            return new Claim(quote, List.of(new Evidence(p.paragraphId(), quote)));
        }).toList();
        if (claims.isEmpty()) throw DocumentInput.invalid("缺少有效段落");
        return new Result("document.extract.result.v1", title, claims.getFirst(), claims, List.of(),
                List.of(language.equals("zh-CN") ? "开发用假模型仅摘录段落，未进行语义归纳。" : "Development fake model: excerpts only; no semantic inference."), "fake-extract-v1");
    }

    public long callCount() { return calls.get(); }

    public static void validate(Result result, List<DocumentInput.Paragraph> paragraphs) {
        if (result == null || !"document.extract.result.v1".equals(result.schemaVersion())
                || result.title() == null || result.title().length() > 100 || result.summary() == null
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
                if (evidence == null || evidence.quote() == null || evidence.quote().isBlank()
                        || paragraphs.stream().noneMatch(p -> p.paragraphId().equals(evidence.paragraphId()) && p.text().contains(evidence.quote())))
                    throw new IllegalStateException("EVIDENCE_INVALID");
            }
        }
        if (result.unknowns().stream().anyMatch(s -> s == null || s.length() > 1000)) throw new IllegalStateException("OUTPUT_INVALID");
    }
}
