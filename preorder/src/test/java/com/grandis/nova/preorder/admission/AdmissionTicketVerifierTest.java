package com.grandis.nova.preorder.admission;

import com.grandis.nova.preorder.support.AdmissionTickets;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmissionTicketVerifierTest {

    static final String CURRENT = "nova-test-current-secret-0123456789";
    static final String PREVIOUS = "nova-test-previous-secret-0123456789";
    static final Long PRODUCT_ID = 101L;
    static final Long CUSTOMER_ID = 1024L;

    /** 발급 시각. 창(30초) 시작은 01:00:00, 만료는 그 + 120초 = 01:02:00(epoch 1790816520). */
    static final Instant ISSUED_AT = Instant.parse("2026-10-01T01:00:07Z");
    static final Instant EXPIRES_AT = Instant.ofEpochSecond(1790816520);

    /**
     * 공통 테스트 벡터 — waiting SignedToken("et_", ttl 120, window 30)으로 만든 값이다.
     * 게이트웨이 이식 뒤에도 양쪽 테스트가 이 값을 같이 쓴다(gateway-auth-tokens.md 5절).
     */
    /** 만료 − 수명(120초) − 시각 오차(30초). 이보다 앞서 발급됐을 수 없다. */
    static final Instant ISSUED_NO_EARLIER_THAN = EXPIRES_AT.minusSeconds(150);

    static final String VECTOR_CURRENT =
            "et_MTAxHzEwMjQfMTc5MDgxNjUyMA.eLyT_jlZvbtd8xkwWxcyH2fvoyaZs0cXeXQbA8E-OKo";
    static final String VECTOR_CURRENT_ID = "0fa344e6a5944743178270c54bd9534f35024cc04e20f20968965a9a992418f5";
    static final String VECTOR_OTHER_PRODUCT =
            "et_MjAyHzc3HzE3OTA4MTY1MjA.z6M2CLHz31DBoddp3bVbXUdfoQofqEWhz0Mee728ztA";
    static final String VECTOR_PREVIOUS_KEY =
            "et_MTAxHzEwMjQfMTc5MDgxNjUyMA.MfGdQ4gHG7gwdzc2Gi6F-N2GI1FB8Xg-yGCM2scz2uI";
    static final String VECTOR_QUEUE_TOKEN =
            "qt_MTAxHzEwMjQfMTc5MDgxNjUyMA.K6Ol3GWYPYAtwU4ngEDQeY9NzaWBxltmHkMJ-pGVJkM";

    @Nested
    class 공통_벡터 {

        @Test
        void waiting_이_발급한_입장권을_받고_ID_는_토큰의_SHA256_이다() {
            assertThat(verifierAt(ISSUED_AT).verify(VECTOR_CURRENT, PRODUCT_ID, CUSTOMER_ID))
                    .contains(new AdmissionTicket(VECTOR_CURRENT_ID, false, ISSUED_NO_EARLIER_THAN));
        }

        @Test
        void 테스트_발급기는_waiting_과_같은_문자열을_만든다() {
            assertThat(AdmissionTickets.issue(CURRENT, PRODUCT_ID, CUSTOMER_ID, ISSUED_AT)).isEqualTo(VECTOR_CURRENT);
            assertThat(AdmissionTickets.issue(CURRENT, PRODUCT_ID, CUSTOMER_ID, ISSUED_AT.plusSeconds(20)))
                    .as("같은 창 안에서는 같은 입장권").isEqualTo(VECTOR_CURRENT);
            assertThat(AdmissionTickets.issue(CURRENT, 202L, 77L, ISSUED_AT)).isEqualTo(VECTOR_OTHER_PRODUCT);
        }
    }

    @Nested
    class 상품과_회원 {

        @Test
        void 다른_상품의_입장권은_거절한다() {
            assertThat(verifierAt(ISSUED_AT).verify(VECTOR_OTHER_PRODUCT, PRODUCT_ID, 77L)).isEmpty();
        }

        @Test
        void 다른_회원의_입장권은_거절한다() {
            assertThat(verifierAt(ISSUED_AT).verify(VECTOR_CURRENT, PRODUCT_ID, 9999L)).isEmpty();
        }
    }

    @Nested
    class 만료 {

        @Test
        void 만료_뒤에도_서버_시각_오차_30초_안이면_유효하다() {
            assertThat(verifierAt(EXPIRES_AT.plusSeconds(29)).verify(VECTOR_CURRENT, PRODUCT_ID, CUSTOMER_ID))
                    .contains(new AdmissionTicket(VECTOR_CURRENT_ID, false, ISSUED_NO_EARLIER_THAN));
        }

        @Test
        void 오차를_넘으면_만료로_알린다() {
            assertThat(verifierAt(EXPIRES_AT.plusSeconds(30)).verify(VECTOR_CURRENT, PRODUCT_ID, CUSTOMER_ID))
                    .contains(new AdmissionTicket(VECTOR_CURRENT_ID, true, ISSUED_NO_EARLIER_THAN));
        }
    }

    @Nested
    class 서명_표기 {

        /** 표기만 다른 같은 서명은 토큰 해시(1회 소비 ID)가 달라 한 입장권을 여러 번 쓰게 된다. */
        @Test
        void 패딩을_붙인_표기는_거절한다() {
            assertThat(verifierAt(ISSUED_AT).verify(VECTOR_CURRENT + "=", PRODUCT_ID, CUSTOMER_ID)).isEmpty();
        }

        @Test
        void 마지막_글자의_안_쓰는_비트를_바꾼_표기는_거절한다() {
            String base = VECTOR_CURRENT.substring(0, VECTOR_CURRENT.length() - 1);

            assertThat(verifierAt(ISSUED_AT).verify(base + "p", PRODUCT_ID, CUSTOMER_ID)).isEmpty();
            assertThat(verifierAt(ISSUED_AT).verify(base + "q", PRODUCT_ID, CUSTOMER_ID)).isEmpty();
            assertThat(verifierAt(ISSUED_AT).verify(VECTOR_CURRENT, PRODUCT_ID, CUSTOMER_ID)).isPresent();
        }
    }

    @Nested
    class 위조 {

        @Test
        void 서명을_고치면_거절한다() {
            String forged = VECTOR_CURRENT.substring(0, VECTOR_CURRENT.length() - 2)
                    + (VECTOR_CURRENT.endsWith("AA") ? "BB" : "AA");

            assertThat(verifierAt(ISSUED_AT).verify(forged, PRODUCT_ID, CUSTOMER_ID)).isEmpty();
        }

        @Test
        void 남의_페이로드에_내_서명을_붙이면_거절한다() {
            String spliced = VECTOR_OTHER_PRODUCT.split("\\.")[0] + "." + VECTOR_CURRENT.split("\\.")[1];

            assertThat(verifierAt(ISSUED_AT).verify(spliced, 202L, 77L)).isEmpty();
        }

        @Test
        void 대기열_토큰은_입장권이_아니다() {
            assertThat(verifierAt(ISSUED_AT).verify(VECTOR_QUEUE_TOKEN, PRODUCT_ID, CUSTOMER_ID)).isEmpty();
        }

        @Test
        void 대기열_토큰의_접두만_바꿔도_서명이_맞지_않는다() {
            String swapped = "et_" + VECTOR_QUEUE_TOKEN.substring(3);

            assertThat(verifierAt(ISSUED_AT).verify(swapped, PRODUCT_ID, CUSTOMER_ID)).isEmpty();
        }

        @Test
        void 다른_비밀로_만든_입장권은_거절한다() {
            String other = AdmissionTickets.issue("some-other-secret-0123456789", PRODUCT_ID, CUSTOMER_ID, ISSUED_AT);

            assertThat(verifierAt(ISSUED_AT).verify(other, PRODUCT_ID, CUSTOMER_ID)).isEmpty();
        }
    }

    @Nested
    class 모양 {

        @Test
        void 비었거나_구분자가_없거나_서명이_깨지면_거절한다() {
            AdmissionTicketVerifier verifier = verifierAt(ISSUED_AT);

            assertThat(verifier.verify(null, PRODUCT_ID, CUSTOMER_ID)).isEmpty();
            assertThat(verifier.verify("", PRODUCT_ID, CUSTOMER_ID)).isEmpty();
            assertThat(verifier.verify("et_abc", PRODUCT_ID, CUSTOMER_ID)).isEmpty();
            assertThat(verifier.verify("et_abc.@@@", PRODUCT_ID, CUSTOMER_ID)).isEmpty();
        }

        @Test
        void 서명이_맞아도_칸이_셋이_아니면_거절한다() {
            String twoFields = AdmissionTickets.issueRaw(CURRENT, "et_", "101" + (char) 0x1f + "1024");
            String fourFields = AdmissionTickets.issueRaw(CURRENT, "et_",
                    "101" + (char) 0x1f + "1024" + (char) 0x1f + "1790816520" + (char) 0x1f + "x");

            assertThat(verifierAt(ISSUED_AT).verify(twoFields, PRODUCT_ID, CUSTOMER_ID)).isEmpty();
            assertThat(verifierAt(ISSUED_AT).verify(fourFields, PRODUCT_ID, CUSTOMER_ID)).isEmpty();
        }

        @Test
        void 만료_칸이_숫자가_아니면_거절한다() {
            String badExp = AdmissionTickets.issueRaw(CURRENT, "et_",
                    "101" + (char) 0x1f + "1024" + (char) 0x1f + "soon");

            assertThat(verifierAt(ISSUED_AT).verify(badExp, PRODUCT_ID, CUSTOMER_ID)).isEmpty();
        }
    }

    @Nested
    class 키_교체 {

        final Instant rolloutEndsAt = ISSUED_AT;

        @Test
        void 교체_중에는_이전_키로_서명한_입장권도_받는다() {
            assertThat(rotatingVerifierAt(ISSUED_AT).verify(VECTOR_PREVIOUS_KEY, PRODUCT_ID, CUSTOMER_ID))
                    .isPresent();
            assertThat(rotatingVerifierAt(ISSUED_AT).verify(VECTOR_CURRENT, PRODUCT_ID, CUSTOMER_ID)).isPresent();
        }

        @Test
        void 교체가_끝나고_수명과_창이_지나면_이전_키를_받지_않는다() {
            Instant closed = rolloutEndsAt.plusSeconds(AdmissionTickets.TTL_SECONDS + AdmissionTickets.WINDOW_SECONDS);

            assertThat(rotatingVerifierAt(closed).verify(VECTOR_PREVIOUS_KEY, PRODUCT_ID, CUSTOMER_ID)).isEmpty();
        }

        @Test
        void 시각_오차가_창보다_크면_오차가_끝날_때까지_이전_키를_받는다() {
            // 만료 01:02:00 + 오차 60초 = 01:03:00 까지 유효. ttl + window 로 닫으면 01:02:37 에 끊긴다.
            AdmissionTicketVerifier verifier = new AdmissionTicketVerifier(
                    new AdmissionTicketProperties(CURRENT, List.of(PREVIOUS), rolloutEndsAt,
                            Duration.ofSeconds(AdmissionTickets.TTL_SECONDS),
                            Duration.ofSeconds(AdmissionTickets.WINDOW_SECONDS), Duration.ofSeconds(60)),
                    Clock.fixed(EXPIRES_AT.plusSeconds(50), ZoneOffset.UTC));

            assertThat(verifier.verify(VECTOR_PREVIOUS_KEY, PRODUCT_ID, CUSTOMER_ID)).isPresent();
        }

        @Test
        void 교체_시각이_없으면_이전_키를_받지_않는다() {
            assertThat(verifierAt(ISSUED_AT).verify(VECTOR_PREVIOUS_KEY, PRODUCT_ID, CUSTOMER_ID)).isEmpty();
        }

        private AdmissionTicketVerifier rotatingVerifierAt(Instant now) {
            return new AdmissionTicketVerifier(properties(CURRENT, List.of(PREVIOUS), rolloutEndsAt),
                    Clock.fixed(now, ZoneOffset.UTC));
        }
    }

    @Nested
    class 설정 {

        @Test
        void 약한_키나_잘못된_교체_설정이면_기동을_막는다() {
            assertThatThrownBy(() -> create(properties("short", List.of(), null)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> create(properties(CURRENT, List.of("short"), ISSUED_AT)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> create(properties(CURRENT, List.of(CURRENT), ISSUED_AT)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> create(properties(CURRENT, List.of(PREVIOUS, PREVIOUS), ISSUED_AT)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> create(properties(CURRENT,
                    List.of(PREVIOUS, PREVIOUS + "a", PREVIOUS + "b"), ISSUED_AT)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> create(properties(CURRENT, List.of(PREVIOUS), null)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> create(new AdmissionTicketProperties(CURRENT, List.of(), null,
                    Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(30))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 환경_변수가_비어_있으면_이전_키_없이_뜬다() {
            new ApplicationContextRunner()
                    .withBean(Clock.class, () -> Clock.fixed(ISSUED_AT, ZoneOffset.UTC))
                    .withUserConfiguration(AdmissionTicketConfig.class, AdmissionTicketVerifier.class)
                    .withPropertyValues("nova.admission-ticket.secret=" + CURRENT,
                            "nova.admission-ticket.previous=",
                            "nova.admission-ticket.rollout-ends-at=")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(AdmissionTicketProperties.class).previous()).isEmpty();
                        assertThat(context.getBean(AdmissionTicketVerifier.class)
                                .verify(VECTOR_CURRENT, PRODUCT_ID, CUSTOMER_ID)).isPresent();
                    });
        }

        private AdmissionTicketVerifier create(AdmissionTicketProperties properties) {
            return new AdmissionTicketVerifier(properties, Clock.fixed(ISSUED_AT, ZoneOffset.UTC));
        }
    }

    private static AdmissionTicketVerifier verifierAt(Instant now) {
        return new AdmissionTicketVerifier(properties(CURRENT, List.of(), null), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static AdmissionTicketProperties properties(String secret, List<String> previous, Instant rolloutEndsAt) {
        return new AdmissionTicketProperties(secret, previous, rolloutEndsAt,
                Duration.ofSeconds(AdmissionTickets.TTL_SECONDS), Duration.ofSeconds(AdmissionTickets.WINDOW_SECONDS),
                Duration.ofSeconds(30));
    }
}
