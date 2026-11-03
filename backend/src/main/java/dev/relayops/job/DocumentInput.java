package dev.relayops.job;

import dev.relayops.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class DocumentInput {
    private DocumentInput() {}
    public record Paragraph(String paragraphId, String text) {}
    public record VersionInput(String base, String revised) {}
    public static final String VERSION_A = "<<<VERSION_A>>>";
    public static final String VERSION_B = "<<<VERSION_B>>>";

    public static String normalize(String text) {
        if (text == null) throw invalid("请填写技术文档");
        // Bound raw input before normalization to avoid unbounded processing.
        if (text.length() > 24000) throw invalid("输入过长");
        text = Normalizer.normalize(text.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC).strip();
        if (text.isBlank() || text.codePointCount(0, text.length()) > 6000
                || text.getBytes(StandardCharsets.UTF_8).length > 24576) throw invalid("输入最多 6000 字符、24576 UTF-8 字节");
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(++i))) throw invalid("输入含无效 Unicode");
            } else if (Character.isLowSurrogate(ch) || (Character.isISOControl(ch) && ch != '\n' && ch != '\t')) {
                throw invalid("输入含不支持的控制字符或二进制内容");
            }
        }
        return text;
    }

    public static List<Paragraph> paragraphs(String text) {
        List<Paragraph> result = new ArrayList<>();
        for (String block : text.split("\\n[\\t ]*\\n+")) {
            if (block.isBlank()) continue;
            while (!block.isEmpty()) {
                int end = block.offsetByCodePoints(0, Math.min(1000, block.codePointCount(0, block.length())));
                result.add(new Paragraph("p%04d".formatted(result.size() + 1), block.substring(0, end)));
                block = block.substring(end);
                if (result.size() > 100) throw invalid("段落数量最多 100");
            }
        }
        return List.copyOf(result);
    }

    public static List<Paragraph> versionParagraphs(String base, String revised) {
        if (base.contains(VERSION_A) || base.contains(VERSION_B) || revised.contains(VERSION_A) || revised.contains(VERSION_B))
            throw invalid("输入不能包含版本保留标记");
        List<Paragraph> result = new ArrayList<>();
        appendPrefixed(result, base, "a");
        appendPrefixed(result, revised, "b");
        return List.copyOf(result);
    }

    public static String versionInput(String base, String revised) {
        versionParagraphs(base, revised);
        return VERSION_A + "\n" + base + "\n" + VERSION_B + "\n" + revised;
    }

    public static VersionInput splitVersion(String text) {
        if (text == null || !text.startsWith(VERSION_A + "\n")) throw new IllegalStateException("UNSUPPORTED_VERSION_INPUT");
        int split = text.indexOf("\n" + VERSION_B + "\n");
        if (split < 0) throw new IllegalStateException("UNSUPPORTED_VERSION_INPUT");
        String base = text.substring((VERSION_A + "\n").length(), split).strip();
        String revised = text.substring(split + ("\n" + VERSION_B + "\n").length()).strip();
        if (base.isBlank() || revised.isBlank()) throw new IllegalStateException("UNSUPPORTED_VERSION_INPUT");
        return new VersionInput(base, revised);
    }

    public static List<Paragraph> versionParagraphs(String stored) {
        VersionInput input = splitVersion(stored);
        return versionParagraphs(input.base(), input.revised());
    }

    public static List<Paragraph> paragraphsFor(String schemaVersion, String text) {
        return TaskType.VERSION_DIFF.schemaVersion.equals(schemaVersion) ? versionParagraphs(text) : paragraphs(text);
    }

    private static void appendPrefixed(List<Paragraph> result, String text, String prefix) {
        int before = result.size();
        for (String block : text.split("\n[\t ]*\n+")) {
            if (block.isBlank()) continue;
            while (!block.isEmpty()) {
                int end = block.offsetByCodePoints(0, Math.min(1000, block.codePointCount(0, block.length())));
                result.add(new Paragraph(prefix + "%04d".formatted(result.size() - before + 1), block.substring(0, end)));
                block = block.substring(end);
                if (result.size() - before > 100) throw invalid("每份文档段落数量最多 100");
            }
        }
    }

    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    public static ApiException invalid(String message) { return new ApiException(422, "VALIDATION_FAILED", message); }
}
