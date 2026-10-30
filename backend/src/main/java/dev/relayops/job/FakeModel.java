package dev.relayops.job;

import java.util.List;
import static dev.relayops.job.DocumentResult.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class FakeModel {
    private final AtomicLong calls = new AtomicLong();
    public DocumentResult extract(String title, List<DocumentInput.Paragraph> paragraphs, String language) {
        calls.incrementAndGet();
        List<Claim> claims = paragraphs.stream().limit(5).map(p -> {
            int end = p.text().offsetByCodePoints(0, Math.min(240, p.text().codePointCount(0, p.text().length())));
            String quote = p.text().substring(0, end);
            return new Claim(quote, List.of(new Evidence(p.paragraphId(), quote)));
        }).toList();
        if (claims.isEmpty()) throw DocumentInput.invalid("缺少有效段落");
        return new DocumentResult("document.extract.result.v1", title, claims.getFirst(), claims, List.of(),
                List.of(language.equals("zh-CN") ? "开发用假模型仅摘录段落，未进行语义归纳。" : "Development fake model: excerpts only; no semantic inference."), "fake-extract-v1");
    }

    public long callCount() { return calls.get(); }

}
