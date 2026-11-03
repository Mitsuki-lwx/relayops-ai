package dev.relayops.job;

import java.util.List;
import static dev.relayops.job.DocumentResult.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class FakeModel {
    public DocumentResult review(String title, List<DocumentInput.Paragraph> paragraphs, String language) {
        var base = extract(title, paragraphs, language);
        var criterion = new DocumentResult.Criterion("开发预览：人工核对原文要求", "人工已经审核原文", "执行原文描述的操作",
                "人工核对行为满足关联原文", base.summary().evidence(), List.of("假模型只演示结构，不生成真实语义验收标准。"));
        return new DocumentResult(TaskType.REQUIREMENTS_REVIEW.schemaVersion,title,base.summary(),List.of(),List.of(),List.of(),"fake-review-v1",
                new DocumentResult.Review(List.of(),List.of(criterion)));
    }
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
