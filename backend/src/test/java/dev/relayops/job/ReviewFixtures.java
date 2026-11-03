package dev.relayops.job;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

final class ReviewFixtures {
    static final String INPUT = "文件上传必须登录。\n\n规范甲要求超时10秒。\n\n规范乙要求超时30秒。";
    static DocumentResult.Review review() {
        var first = List.of(new DocumentResult.Evidence("p0001","文件上传必须登录。"));
        return new DocumentResult.Review(List.of(
                new DocumentResult.Finding("GAP","需要澄清文件大小上限","单次文件大小上限是多少？",first),
                new DocumentResult.Finding("CONFLICT","两处超时要求不同","应以哪个超时值为准？",List.of(new DocumentResult.Evidence("p0002","超时10秒"),new DocumentResult.Evidence("p0003","超时30秒")))),
                List.of(new DocumentResult.Criterion("登录前提","用户尚未登录","尝试上传文件","不接受上传",first,List.of("拒绝的状态码和提示需人工确认。"))));
    }
    static DocumentResult result(DocumentResult.Review review) {
        return new DocumentResult(TaskType.REQUIREMENTS_REVIEW.schemaVersion,"评审",new DocumentResult.Claim("上传需要登录",List.of(new DocumentResult.Evidence("p0001","文件上传必须登录。"))),List.of(),List.of(),List.of(),"fake-review-v1",review);
    }
    static String json(JsonMapper json) { return json.writeValueAsString(Map.of("summary",result(review()).summary(),"review",review())); }
}
