package dev.relayops.job;

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
}
