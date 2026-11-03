package dev.relayops.job;

import dev.relayops.common.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class RequirementsReviewTest {
    @Test void validatesDraftsAndDistinctConflictReferences() {
        DocumentResult.validate(ReviewFixtures.result(ReviewFixtures.review()),DocumentInput.paragraphs(ReviewFixtures.INPUT));
        var one=List.of(new DocumentResult.Evidence("p0002","超时10秒"));
        for(var refs:List.of(one,List.of(one.getFirst(),one.getFirst()))) {
            var review=new DocumentResult.Review(List.of(new DocumentResult.Finding("CONFLICT","冲突","选择哪个？",refs)),List.of());
            assertThrows(IllegalStateException.class,()->DocumentResult.validate(ReviewFixtures.result(review),DocumentInput.paragraphs(ReviewFixtures.INPUT)));
        }
    }
    @Test void rejectsUnmatchedQuotesUnknownKindsAndIncompleteCriteria() {
        var refs=List.of(new DocumentResult.Evidence("p0001","虚构引用"));
        var forged=new DocumentResult.Review(List.of(new DocumentResult.Finding("GAP","问题","如何确认？",refs)),List.of());
        assertEquals("EVIDENCE_INVALID",assertThrows(IllegalStateException.class,()->DocumentResult.validate(ReviewFixtures.result(forged),DocumentInput.paragraphs(ReviewFixtures.INPUT))).getMessage());
        var valid=ReviewFixtures.review().findings().getFirst();
        for(var bad:List.of(new DocumentResult.Finding("EXECUTE","问题","如何确认？",valid.evidence()),new DocumentResult.Finding("GAP","问题","",valid.evidence()))) {
            assertThrows(IllegalStateException.class,()->DocumentResult.validate(ReviewFixtures.result(new DocumentResult.Review(List.of(bad),List.of())),DocumentInput.paragraphs(ReviewFixtures.INPUT)));
        }
        var c=ReviewFixtures.review().acceptanceCriteria().getFirst();
        var bad=new DocumentResult.Criterion(c.title(),c.given(),c.when(),"",c.evidence(),c.assumptions());
        assertThrows(IllegalStateException.class,()->DocumentResult.validate(ReviewFixtures.result(new DocumentResult.Review(List.of(),List.of(bad))),DocumentInput.paragraphs(ReviewFixtures.INPUT)));
    }
    @Test void boundsReviewFanoutAndPreservesLegacyWireShape() {
        var review=ReviewFixtures.review();
        var tooMany=new DocumentResult.Review(java.util.Collections.nCopies(7,review.findings().getFirst()),List.of());
        assertThrows(IllegalStateException.class,()->DocumentResult.validate(ReviewFixtures.result(tooMany),DocumentInput.paragraphs(ReviewFixtures.INPUT)));
        var fake=new FakeModel();var old=fake.extract("旧任务",DocumentInput.paragraphs(ReviewFixtures.INPUT),"zh-CN");
        assertFalse(JsonMapper.builder().build().readTree(JsonMapper.builder().build().writeValueAsString(old)).has("review"));
        var result=fake.review("新任务",DocumentInput.paragraphs(ReviewFixtures.INPUT),"zh-CN");
        DocumentResult.validate(result,DocumentInput.paragraphs(ReviewFixtures.INPUT));assertEquals(2,fake.callCount());
        assertEquals(TaskType.REQUIREMENTS_REVIEW.schemaVersion,result.schemaVersion());
    }
    @Test void unknownPersistedSchemaDoesNotBreakJobListing() {
        assertNull(TaskType.nameIfKnown("future.result.v1"));
        assertEquals("EXTRACT",TaskType.nameIfKnown(TaskType.EXTRACT.schemaVersion));
        assertEquals("REQUIREMENTS_REVIEW",TaskType.nameIfKnown(TaskType.REQUIREMENTS_REVIEW.schemaVersion));
    }
    @Test void reviewOutputCapIsPartOfFrozenConfiguration() throws Exception {
        try(var stub=new StubProvider()) {
            var first=new ModelGateway(new FakeModel(),stub.json,"compatible",stub.endpoint(),"test-key-not-real","fixture-model",5,4096,8192,"max_tokens",true,true);
            var second=new ModelGateway(new FakeModel(),stub.json,"compatible",stub.endpoint(),"test-key-not-real","fixture-model",5,4096,8193,"max_tokens",true,true);
            var extractOnly=new ModelGateway(new FakeModel(),stub.json,"compatible",stub.endpoint(),"test-key-not-real","fixture-model",5,2048,8192,"max_tokens",true,true);
            assertNotEquals(first.currentProfile(TaskType.REQUIREMENTS_REVIEW).profileId(),second.currentProfile(TaskType.REQUIREMENTS_REVIEW).profileId());
            assertEquals(first.currentProfile().profileId(),second.currentProfile().profileId());
            assertEquals(first.currentProfile(TaskType.REQUIREMENTS_REVIEW).profileId(),extractOnly.currentProfile(TaskType.REQUIREMENTS_REVIEW).profileId());
            assertNotEquals(first.currentProfile().profileId(),extractOnly.currentProfile().profileId());
            assertEquals("review.compat.v2",first.currentProfile(TaskType.REQUIREMENTS_REVIEW).promptVersion());
        }
    }
    @Test void compatibleReviewUsesIndependentPromptAndProfile() throws Exception {
        try(var stub=new StubProvider();var gateway=stub.gateway(5)) {
            String oldProfile=gateway.currentProfile().profileId();
            var profile=gateway.currentProfile(TaskType.REQUIREMENTS_REVIEW);
            assertNotEquals(oldProfile,profile.profileId());assertTrue(profile.profileId().length()<=40);
            stub.content=ReviewFixtures.json(stub.json);
            var result=gateway.prepare(profile.profileId(),"评审",DocumentInput.paragraphs(ReviewFixtures.INPUT),"zh-CN").invoke();
            DocumentResult.validate(result,DocumentInput.paragraphs(ReviewFixtures.INPUT));
            assertEquals(profile.profileId(),result.modelProfile());assertEquals(1,stub.calls.get());
            assertTrue(stub.request.path("messages").get(0).path("content").asText().contains("Given" ) || stub.request.path("messages").get(0).path("content").asText().contains("precondition"));
            assertEquals(8192,stub.request.path("max_tokens").asInt());
            assertEquals(oldProfile,gateway.currentProfile().profileId());
        }
    }

    @Test void debugRealVersionOutput() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var root = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("../artifacts/debug-version-output.json")));
        var content = root.path("data").path("choices").get(0).path("message").path("content").asText();
        var json = mapper.readTree(content);
        var summary = mapper.readValue(json.path("summary").toString(), DocumentResult.Claim.class);
        var diff = mapper.readValue(json.path("diff").toString(), DocumentResult.VersionDiff.class);
        var paragraphs = DocumentInput.versionParagraphs("文件上传必须登录。\n\n上传内容必须是UTF-8纯文本。\n\n日志保留30天。",
                "文件上传必须登录，并限制单个文件最大20MB。\n\n上传内容必须是UTF-8纯文本。\n\n日志保留90天。\n\n失败上传必须返回413。");
        var result = new DocumentResult(TaskType.VERSION_DIFF.schemaVersion, "对比", summary, List.of(), List.of(), List.of(), "fake-version-v1", null, diff);
        System.out.println("DIFF " + diff);
        DocumentResult.validate(result, paragraphs);
    }

    @Test void versionInputRoundTripsWithPrefixedParagraphs() {
        String stored = DocumentInput.versionInput("基线要求A。\n\n第二段。", "修订要求B。");
        var paragraphs = DocumentInput.versionParagraphs(stored);
        assertEquals(List.of("a0001", "a0002", "b0001"), paragraphs.stream().map(DocumentInput.Paragraph::paragraphId).toList());
        var split = DocumentInput.splitVersion(stored);
        assertEquals("基线要求A。\n\n第二段。", split.base());
        assertEquals("修订要求B。", split.revised());
        assertThrows(ApiException.class, () -> DocumentInput.versionInput("含<<<VERSION_A>>>标记", "B"));
    }

    @Test void validatesVersionDiffEvidenceOnBothSides() {
        var paragraphs = DocumentInput.versionParagraphs("基线要求A。", "修订要求B。");
        var a = List.of(new DocumentResult.Evidence("a0001", "基线要求A。"));
        var b = List.of(new DocumentResult.Evidence("b0001", "修订要求B。"));
        var summary = new DocumentResult.Claim("版本变化", a);
        var criterion = new DocumentResult.Criterion("影响确认", "人工已比较", "按修订执行", "符合修订原文", b, List.of());
        var valid = new DocumentResult.VersionDiff(List.of(
                new DocumentResult.Change("ADDED", "新增", List.of(), b),
                new DocumentResult.Change("REMOVED", "删除", a, List.of()),
                new DocumentResult.Change("MODIFIED", "修改", a, b)), List.of(summary), List.of(criterion));
        DocumentResult.validate(new DocumentResult(TaskType.VERSION_DIFF.schemaVersion, "对比", summary, List.of(), List.of(), List.of(), "fake-version-v1", null, valid), paragraphs);
        for (var bad : List.of(
                new DocumentResult.VersionDiff(List.of(new DocumentResult.Change("ADDED", "新增", a, b)), List.of(), List.of()),
                new DocumentResult.VersionDiff(List.of(new DocumentResult.Change("REMOVED", "删除", a, b)), List.of(), List.of()),
                new DocumentResult.VersionDiff(List.of(new DocumentResult.Change("MODIFIED", "修改", a, List.of())), List.of(), List.of()),
                new DocumentResult.VersionDiff(List.of(new DocumentResult.Change("OTHER", "其他", List.of(), b)), List.of(), List.of()))) {
            assertThrows(IllegalStateException.class, () -> DocumentResult.validate(
                    new DocumentResult(TaskType.VERSION_DIFF.schemaVersion, "对比", summary, List.of(), List.of(), List.of(), "fake-version-v1", null, bad), paragraphs));
        }
    }

    @Test void compatibleVersionUsesIndependentPromptAndProfile() throws Exception {
        try (var stub = new StubProvider(); var gateway = stub.gateway(5)) {
            var profile = gateway.currentProfile(TaskType.VERSION_DIFF);
            assertNotEquals(gateway.currentProfile().profileId(), profile.profileId());
            assertNotEquals(gateway.currentProfile(TaskType.REQUIREMENTS_REVIEW).profileId(), profile.profileId());
            assertEquals("version.compat.v2", profile.promptVersion());
            var a = List.of(new DocumentResult.Evidence("a0001", "基线要求A。"));
            var b = List.of(new DocumentResult.Evidence("b0001", "修订要求B。"));
            var summary = new DocumentResult.Claim("版本变化", a);
            var diff = new DocumentResult.VersionDiff(List.of(new DocumentResult.Change("MODIFIED", "修改", a, b)), List.of(), List.of());
            stub.content = stub.json.writeValueAsString(java.util.Map.of("summary", summary, "diff", diff));
            var paragraphs = DocumentInput.versionParagraphs("基线要求A。", "修订要求B。");
            var result = gateway.prepare(profile.profileId(), "对比", paragraphs, "zh-CN").invoke();
            DocumentResult.validate(result, paragraphs);
            assertEquals(profile.profileId(), result.modelProfile());
            assertEquals(1, stub.calls.get());
            assertTrue(stub.request.path("messages").get(0).path("content").asText().contains("ADDED|REMOVED|MODIFIED"));
        }
    }
}
