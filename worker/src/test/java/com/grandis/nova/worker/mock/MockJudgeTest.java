package com.grandis.nova.worker.mock;

import com.grandis.nova.worker.mock.MockReply.TransportFailure;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 응답 · 상황별 판정. HTTP 상태보다 errorCode 를 먼저 본다. */
class MockJudgeTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void 등록_201_은_성공이고_번호를_돌려준다() {
        Judgement judgement = MockJudge.register(json(201, """
                {"externalKey":"K-1","externalNumber":"R-20261012-0000000001","status":"ACTIVE"}"""));

        assertThat(judgement.verdict()).isEqualTo(Verdict.SUCCESS);
        assertThat(judgement.externalNumber()).isEqualTo("R-20261012-0000000001");
    }

    @Test
    void 등록_깨진_201_과_201_아닌_2xx_는_결과_모름이다() {
        assertThat(MockJudge.register(reply(201)).verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(MockJudge.register(json(201, "{\"status\":\"ACTIVE\"}")).verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(MockJudge.register(json(200, "{\"externalNumber\":\"R-1\"}")).verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(MockJudge.register(reply(204)).verdict()).isEqualTo(Verdict.UNKNOWN);
    }

    @Test
    void 등록_500_UPSTREAM_UNAVAILABLE_은_곧바로_다시_보낼_수_있는_일시_실패다() {
        Judgement judgement = MockJudge.register(error(500, "UPSTREAM_UNAVAILABLE"));

        assertThat(judgement.verdict()).isEqualTo(Verdict.TRANSIENT);
        assertThat(judgement.retryNow()).isTrue();
        assertThat(judgement.errorCode()).isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    @Test
    void 등록_500_의_다른_errorCode_와_본문_없는_500_은_결과_모름이다() {
        assertThat(MockJudge.register(error(500, "SOMETHING_ELSE")).verdict()).isEqualTo(Verdict.UNKNOWN);
        Judgement noBody = MockJudge.register(reply(500));
        assertThat(noBody.verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(noBody.retryNow()).isFalse();
    }

    @Test
    void 등록_HTML_502_503_은_일시_실패이고_504_는_결과_모름이다() {
        for (int status : new int[]{502, 503}) {
            Judgement judgement = MockJudge.register(reply(status));
            assertThat(judgement.verdict()).as("%d", status).isEqualTo(Verdict.TRANSIENT);
            assertThat(judgement.retryNow()).as("%d", status).isTrue();
        }
        assertThat(MockJudge.register(reply(504)).verdict()).isEqualTo(Verdict.UNKNOWN);
    }

    @Test
    void 등록_503_이_Retry_After_를_주면_그만큼_기다리고_곧바로_다시_보내지_않는다() {
        Judgement judgement = MockJudge.register(MockReply.response(503, null, Duration.ofSeconds(7)));

        assertThat(judgement.verdict()).isEqualTo(Verdict.TRANSIENT);
        assertThat(judgement.retryNow()).isFalse();
        assertThat(judgement.retryAfter()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void 등록_429_는_곧바로_다시_보내지_않는_일시_실패다() {
        Judgement judgement = MockJudge.register(MockReply.response(429, null, Duration.ofSeconds(3)));

        assertThat(judgement.verdict()).isEqualTo(Verdict.TRANSIENT);
        assertThat(judgement.retryNow()).isFalse();
        assertThat(judgement.retryAfter()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void 등록을_외부가_거절하면_실패_확정이고_이미_있는_번호를_싣는다() {
        assertThat(MockJudge.register(error(409, "KEY_CANCELED")).verdict()).isEqualTo(Verdict.REJECTED);
        assertThat(MockJudge.register(error(400, "INVALID_REQUEST")).verdict()).isEqualTo(Verdict.REJECTED);
        Judgement mismatch = MockJudge.register(json(422, """
                {"errorCode":"KEY_PAYLOAD_MISMATCH","errorMessage":"다릅니다","externalNumber":"R-9"}"""));
        assertThat(mismatch.verdict()).isEqualTo(Verdict.REJECTED);
        assertThat(mismatch.errorCode()).isEqualTo("KEY_PAYLOAD_MISMATCH");
        assertThat(mismatch.errorMessage()).isEqualTo("다릅니다");
        assertThat(mismatch.externalNumber()).isEqualTo("R-9");
    }

    @Test
    void errorCode_없는_3xx_4xx_와_없는_경로는_설정_오류다() {
        for (int status : new int[]{301, 302, 404, 405, 415}) {
            assertThat(MockJudge.register(reply(status)).verdict()).as("%d", status).isEqualTo(Verdict.MISCONFIGURED);
        }
        assertThat(MockJudge.register(error(404, "NO_SUCH_ENDPOINT")).verdict()).isEqualTo(Verdict.MISCONFIGURED);
        assertThat(MockJudge.lookup(error(404, "NO_SUCH_ENDPOINT")).verdict()).isEqualTo(Verdict.MISCONFIGURED);
        assertThat(MockJudge.cancel(reply(404)).verdict()).isEqualTo(Verdict.MISCONFIGURED);
    }

    @Test
    void 등록을_연결하지_못하면_일시_실패이고_응답을_못_받으면_결과_모름이다() {
        Judgement connect = MockJudge.register(MockReply.failed(TransportFailure.CONNECT));
        assertThat(connect.verdict()).isEqualTo(Verdict.TRANSIENT);
        assertThat(connect.retryNow()).isTrue();
        assertThat(connect.errorMessage()).isEqualTo("CONNECT");

        for (TransportFailure failure : new TransportFailure[]{TransportFailure.READ_TIMEOUT, TransportFailure.BROKEN}) {
            Judgement judgement = MockJudge.register(MockReply.failed(failure));
            assertThat(judgement.verdict()).as(failure.name()).isEqualTo(Verdict.UNKNOWN);
            assertThat(judgement.retryNow()).as(failure.name()).isFalse();
        }
    }

    @Test
    void 조회에_등록이_있으면_성공이고_그_번호를_돌려준다() {
        Judgement judgement = MockJudge.lookup(json(200, """
                {"externalKey":"K-1","cancelMarkerAt":null,"storedOutcome":"SUCCESS",
                 "registrations":[{"externalNumber":"R-7","status":"ACTIVE","canceledAt":null}]}"""));

        assertThat(judgement.verdict()).isEqualTo(Verdict.SUCCESS);
        assertThat(judgement.externalNumber()).isEqualTo("R-7");
    }

    @Test
    void 조회에_취소_표식이_있으면_취소된_키로_실패_확정이다() {
        Judgement afterRegister = MockJudge.lookup(json(200, """
                {"externalKey":"K-1","cancelMarkerAt":"2026-10-12T01:00:00Z","storedOutcome":null,
                 "registrations":[{"externalNumber":"R-7","status":"CANCELED"}]}"""));
        Judgement markerOnly = MockJudge.lookup(json(200, """
                {"externalKey":"K-1","cancelMarkerAt":"2026-10-12T01:00:00Z","registrations":[]}"""));

        assertThat(afterRegister.verdict()).isEqualTo(Verdict.REJECTED);
        assertThat(afterRegister.errorCode()).isEqualTo("KEY_CANCELED");
        assertThat(markerOnly.verdict()).isEqualTo(Verdict.REJECTED);
    }

    /** 같은 404 라도 errorCode 가 NOT_FOUND 면 정상 응답(등록 없음)이고, 없으면 설정 오류다. */
    @Test
    void 조회_404_NOT_FOUND_는_등록_없음이고_errorCode_없는_404_는_설정_오류다() {
        assertThat(MockJudge.lookup(error(404, "NOT_FOUND")).verdict()).isEqualTo(Verdict.ABSENT);
        assertThat(MockJudge.lookup(reply(404)).verdict()).isEqualTo(Verdict.MISCONFIGURED);
    }

    @Test
    void 조회가_실패하면_이번_차례는_알_수_없다() {
        assertThat(MockJudge.lookup(reply(500)).verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(MockJudge.lookup(reply(503)).verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(MockJudge.lookup(MockReply.failed(TransportFailure.READ_TIMEOUT)).verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(MockJudge.lookup(MockReply.failed(TransportFailure.CONNECT)).verdict()).isEqualTo(Verdict.TRANSIENT);
        assertThat(MockJudge.lookup(json(200, "{\"registrations\":[]}")).verdict()).isEqualTo(Verdict.UNKNOWN);
    }

    @Test
    void 취소_200_은_모르는_키여도_성공이다() {
        Judgement known = MockJudge.cancel(json(200, """
                {"externalKey":"K-1","externalNumbers":["R-7"],"hadActiveRegistration":true}"""));
        Judgement unknownKey = MockJudge.cancel(json(200, """
                {"externalKey":"K-2","externalNumbers":[],"hadActiveRegistration":false}"""));

        assertThat(known.verdict()).isEqualTo(Verdict.SUCCESS);
        assertThat(known.externalNumber()).isEqualTo("R-7");
        assertThat(unknownKey.verdict()).isEqualTo(Verdict.SUCCESS);
        assertThat(unknownKey.externalNumber()).isNull();
    }

    @Test
    void 취소를_외부가_거절하면_실패_확정이다() {
        assertThat(MockJudge.cancel(error(400, "INVALID_REQUEST")).verdict()).isEqualTo(Verdict.REJECTED);
        assertThat(MockJudge.cancel(error(404, "NOT_FOUND")).verdict()).isEqualTo(Verdict.REJECTED);
    }

    /** 취소는 여러 번 보내도 결과가 같아서 조회 없이 다시 보낸다. */
    @Test
    void 취소의_5xx_연결_실패_응답_없음은_모두_일시_실패다() {
        assertThat(MockJudge.cancel(error(500, "UPSTREAM_UNAVAILABLE")).verdict()).isEqualTo(Verdict.TRANSIENT);
        for (int status : new int[]{500, 502, 503, 504}) {
            assertThat(MockJudge.cancel(reply(status)).verdict()).as("%d", status).isEqualTo(Verdict.TRANSIENT);
        }
        for (TransportFailure failure : TransportFailure.values()) {
            Judgement judgement = MockJudge.cancel(MockReply.failed(failure));
            assertThat(judgement.verdict()).as(failure.name()).isEqualTo(Verdict.TRANSIENT);
            assertThat(judgement.retryNow()).as(failure.name()).isTrue();
        }
        assertThat(MockJudge.cancel(MockReply.response(429, null, null)).retryNow()).isFalse();
    }

    private static MockReply json(int status, String body) {
        return MockReply.response(status, JSON.readTree(body), null);
    }

    private static MockReply error(int status, String errorCode) {
        return json(status, """
                {"errorCode":"%s","errorMessage":"message","replayable":false,"externalNumber":null}"""
                .formatted(errorCode));
    }

    /** 본문이 없거나 JSON 이 아닌 응답(HTML 등). */
    private static MockReply reply(int status) {
        return MockReply.response(status, null, null);
    }
}
