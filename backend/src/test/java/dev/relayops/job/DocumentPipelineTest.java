package dev.relayops.job;

import dev.relayops.common.ApiException;
import dev.relayops.common.PayloadCipher;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentPipelineTest {
    @Test
    void normalizesEquivalentDocuments() {
        assertEquals("é\n\n约束", DocumentInput.normalize(" e\u0301\r\n\r\n约束 "));
        assertEquals(DocumentInput.hash(DocumentInput.normalize("e\u0301")), DocumentInput.hash(DocumentInput.normalize("é")));
    }

    @Test
    void countsUnicodeCodePointsRatherThanUtf16Units() {
        String boundary = "😀".repeat(6000);
        assertEquals(boundary, DocumentInput.normalize(boundary));
        assertThrows(ApiException.class, () -> DocumentInput.normalize(boundary + "😀"));
    }

    @Test
    void rejectsBinaryControlsAndBrokenSurrogates() {
        for (String value : List.of("\u0000binary", "\u001b[31m", "broken\ud800", "broken\udc00", "\n  "))
            assertThrows(ApiException.class, () -> DocumentInput.normalize(value));
    }

    @Test
    void splitsLongParagraphsWithoutBreakingUnicode() {
        var paragraphs = DocumentInput.paragraphs("😀".repeat(1001) + "\n\n第二段");
        assertEquals(3, paragraphs.size());
        assertEquals("p0002", paragraphs.get(1).paragraphId());
        assertEquals("😀", paragraphs.get(1).text());
        assertEquals(1000, paragraphs.getFirst().text().codePointCount(0, paragraphs.getFirst().text().length()));
    }

    @Test
    void limitsParagraphFanout() {
        assertThrows(ApiException.class, () -> DocumentInput.paragraphs("a\n\n".repeat(101)));
    }

    @Test
    void fakeResponseHasExactReferencesAndOneCountedCall() {
        var model = new FakeModel();
        var paragraphs = DocumentInput.paragraphs("只有事务提交后才能返回已受理。\n\n回调失败不重新执行模型。");
        var result = model.extract("可靠性约束", paragraphs, "zh-CN");
        DocumentResult.validate(result, paragraphs);
        assertEquals(1, model.callCount());
        assertEquals("p0001", result.summary().evidence().getFirst().paragraphId());
    }

    @Test
    void fabricatedQuotesCannotBeSaved() {
        var paragraphs = DocumentInput.paragraphs("原文没有性能承诺");
        var forged = new DocumentResult.Claim("性能提高十倍", List.of(new DocumentResult.Evidence("p0001", "性能提高十倍")));
        var result = new DocumentResult("document.extract.result.v1", "标题", forged, List.of(), List.of(), List.of(), "fake-extract-v1");
        assertEquals("EVIDENCE_INVALID", assertThrows(IllegalStateException.class, () -> DocumentResult.validate(result, paragraphs)).getMessage());
    }

    @Test
    void encryptionIsRandomizedAndBoundToItsJobAndPayloadType() {
        var cipher = new PayloadCipher(Base64.getEncoder().encodeToString(new byte[32]));
        byte[] first = cipher.encrypt("敏感文档", "workspace:job:input");
        byte[] second = cipher.encrypt("敏感文档", "workspace:job:input");
        assertFalse(java.util.Arrays.equals(first, second));
        assertEquals("敏感文档", cipher.decrypt(first, "workspace:job:input"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(first, "another:job:input"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(first, "workspace:job:result"));
        first[first.length - 1] ^= 1;
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(first, "workspace:job:input"));
    }
}
