package com.grandis.nova.waitingroom.domain.queue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmissionTicketTest {

    static final String CURRENT = "nova-test-current-secret-0123456789";
    static final String PREVIOUS = "nova-test-previous-secret-0123456789";

    /** 발급 시각. 창(30초) 시작은 01:00:00, 만료는 그 + 120초 = 01:02:00(epoch 1790816520). */
    static final Instant ISSUED_AT = Instant.parse("2026-10-01T01:00:07Z");

    static final String PRODUCT = "00000000-0000-7000-8000-000000000101";
    static final String CUSTOMER = "00000000-0000-7000-8000-000000001024";
    static final String OTHER_PRODUCT = "00000000-0000-7000-8000-000000000202";
    static final String OTHER_CUSTOMER = "00000000-0000-7000-8000-000000000077";

    /** preorder 의 입장권 검증 테스트와 같은 공통 테스트 벡터. 한쪽 서명 규칙이 바뀌면 여기서 먼저 깨진다. */
    static final String VECTOR_CURRENT = "et_MDAwMDAwMDAtMDAwMC03MDAwLTgwMDAtMDAwMDAwMDAwMTAxHzAwMDAwMDAwLTAwMDAtNzAwMC04MDAwLTAwMDAwMDAwMTAyNB8xNzkwODE2NTIw.QDp7_sRDfjNKhq8Hw2y0yzpizrzNBYaXSe5Lhy-nh1M";
    static final String VECTOR_OTHER_PRODUCT = "et_MDAwMDAwMDAtMDAwMC03MDAwLTgwMDAtMDAwMDAwMDAwMjAyHzAwMDAwMDAwLTAwMDAtNzAwMC04MDAwLTAwMDAwMDAwMDA3Nx8xNzkwODE2NTIw.5UT5cldXa-INLn6aW4qgQtoFq0HD-dYESzzSzgtsl44";
    static final String VECTOR_SIGNED_BY_PREVIOUS = "et_MDAwMDAwMDAtMDAwMC03MDAwLTgwMDAtMDAwMDAwMDAwMTAxHzAwMDAwMDAwLTAwMDAtNzAwMC04MDAwLTAwMDAwMDAwMTAyNB8xNzkwODE2NTIw.DTOEEcyaD-PLgXh-Ndka_YHVQpeF6V5cQa2znplVwR8";
    static final String VECTOR_QUEUE_PREFIX = "qt_MDAwMDAwMDAtMDAwMC03MDAwLTgwMDAtMDAwMDAwMDAwMTAxHzAwMDAwMDAwLTAwMDAtNzAwMC04MDAwLTAwMDAwMDAwMTAyNB8xNzkwODE2NTIw.mgG-uqldBdt5zaFhDeLlCzhMtSxnnzLEMmms_vrCDGA";

    private final AdmissionTicket ticket = AdmissionTicket.of(CURRENT, List.of(), null);

    @Nested
    class 공통_벡터 {

        @Test
        void preorder_와_같은_입장권을_발급하고_같은_창_안에서는_같은_값이다() {
            assertThat(ticket.issue(PRODUCT, CUSTOMER, ISSUED_AT)).isEqualTo(VECTOR_CURRENT);
            assertThat(ticket.issue(PRODUCT, CUSTOMER, ISSUED_AT.plusSeconds(20))).isEqualTo(VECTOR_CURRENT);
            assertThat(ticket.issue(OTHER_PRODUCT, OTHER_CUSTOMER, ISSUED_AT)).isEqualTo(VECTOR_OTHER_PRODUCT);
        }

        @Test
        void 옛_키_벡터와_대기_토큰_접두_벡터도_같다() {
            assertThat(SignedToken.of("et_", 120, 30, PREVIOUS, List.of(), null).issue(PRODUCT, CUSTOMER, ISSUED_AT))
                    .isEqualTo(VECTOR_SIGNED_BY_PREVIOUS);
            assertThat(SignedToken.of("qt_", 120, 30, CURRENT, List.of(), null).issue(PRODUCT, CUSTOMER, ISSUED_AT))
                    .isEqualTo(VECTOR_QUEUE_PREFIX);
        }

        @Test
        void 벡터를_검증하면_회원을_돌려준다() {
            assertThat(ticket.verify(VECTOR_CURRENT, PRODUCT, ISSUED_AT)).contains(CUSTOMER);
        }
    }

    @Nested
    class 만료_구분 {

        @Test
        void 서명_모델이_맞으면_만료돼도_회원과_만료를_알려_주고_만료_시각을_발급식대로_낸다() {
            Instant expiresAt = Instant.ofEpochSecond(1790816520);

            SignedToken.Holder holder = ticket.authenticate(VECTOR_CURRENT, PRODUCT, expiresAt.plusSeconds(3_600)).orElseThrow();

            assertThat(holder).isEqualTo(new SignedToken.Holder(CUSTOMER, expiresAt));
            assertThat(holder.expired(expiresAt.minusSeconds(1))).isFalse();
            assertThat(holder.expired(expiresAt)).isTrue();
            assertThat(ticket.expiresAt(ISSUED_AT)).isEqualTo(expiresAt);
        }

        @Test
        void 서명이나_모델이_어긋나면_만료와_상관없이_거절한다() {
            assertThat(ticket.authenticate(VECTOR_OTHER_PRODUCT, PRODUCT, ISSUED_AT)).isEmpty();
            assertThat(ticket.authenticate(VECTOR_SIGNED_BY_PREVIOUS, PRODUCT, ISSUED_AT)).isEmpty();
        }
    }

    @Nested
    class 거절 {

        @Test
        void 다른_상품의_입장권은_거절한다() {
            assertThat(ticket.verify(VECTOR_OTHER_PRODUCT, PRODUCT, ISSUED_AT)).isEmpty();
        }

        @Test
        void 만료_시각부터는_거절한다() {
            Instant expiresAt = Instant.ofEpochSecond(1790816520);

            assertThat(ticket.verify(VECTOR_CURRENT, PRODUCT, expiresAt.minusSeconds(1))).contains(CUSTOMER);
            assertThat(ticket.verify(VECTOR_CURRENT, PRODUCT, expiresAt)).isEmpty();
        }

        @Test
        void 대기_토큰을_입장권으로_쓸_수_없다() {
            String swapped = "et_" + VECTOR_QUEUE_PREFIX.substring(3);

            assertThat(ticket.verify(VECTOR_QUEUE_PREFIX, PRODUCT, ISSUED_AT)).isEmpty();
            assertThat(ticket.verify(swapped, PRODUCT, ISSUED_AT)).as("접두가 서명에 들어간다").isEmpty();
        }

        @Test
        void 서명이나_모양이_틀리면_거절한다() {
            String tampered = VECTOR_CURRENT.substring(0, VECTOR_CURRENT.length() - 1) + "A";

            assertThat(ticket.verify(tampered, PRODUCT, ISSUED_AT)).isEmpty();
            assertThat(ticket.verify("et_no-separator", PRODUCT, ISSUED_AT)).isEmpty();
            assertThat(ticket.verify("et_x.!!!", PRODUCT, ISSUED_AT)).isEmpty();
            assertThat(ticket.verify(null, PRODUCT, ISSUED_AT)).isEmpty();
            assertThat(ticket.verify(VECTOR_CURRENT + "x".repeat(600), PRODUCT, ISSUED_AT)).as("길이 상한").isEmpty();
        }

        @Test
        void 서명을_다르게_표기한_같은_입장권은_거절한다_문자열로_소비를_기록하는_쪽이_재사용으로_보지_않게() {
            String body = VECTOR_CURRENT.substring(0, VECTOR_CURRENT.length() - 1);

            assertThat(ticket.verify(VECTOR_CURRENT + "=", PRODUCT, ISSUED_AT)).as("패딩").isEmpty();
            assertThat(ticket.verify(body + "p", PRODUCT, ISSUED_AT)).as("끝 글자의 안 쓰는 비트").isEmpty();
            assertThat(ticket.verify(body + "q", PRODUCT, ISSUED_AT)).isEmpty();
        }

        @Test
        void 옛_키로_서명한_입장권은_옛_키를_받기로_하지_않았으면_거절한다() {
            assertThat(ticket.verify(VECTOR_SIGNED_BY_PREVIOUS, PRODUCT, ISSUED_AT)).isEmpty();
        }
    }

    @Nested
    class 키_교체 {

        static final Instant ROLLOUT_ENDS_AT = Instant.parse("2026-10-01T01:00:00Z");

        private final AdmissionTicket rotating = AdmissionTicket.of(CURRENT, List.of(PREVIOUS), ROLLOUT_ENDS_AT);

        @Test
        void 교체_창_안에서는_옛_키_입장권도_받고_횟수를_센다() {
            assertThat(rotating.verify(VECTOR_SIGNED_BY_PREVIOUS, PRODUCT, ISSUED_AT)).contains(CUSTOMER);
            assertThat(rotating.verify(VECTOR_CURRENT, PRODUCT, ISSUED_AT)).contains(CUSTOMER);
            assertThat(rotating.acceptedByPrevious()).isEqualTo(1);
        }

        @Test
        void 창은_배포_끝_더하기_수명_더하기_창까지다() {
            Instant closesAt = ROLLOUT_ENDS_AT.plusSeconds(AdmissionTicket.TTL_SEC + AdmissionTicket.WINDOW_SEC);
            String previousTicket = SignedToken.of("et_", 120, 30, PREVIOUS, List.of(), null)
                    .issue(PRODUCT, CUSTOMER, closesAt.minusSeconds(1));

            assertThat(rotating.verify(previousTicket, PRODUCT, closesAt.minusSeconds(1))).contains(CUSTOMER);
            assertThat(rotating.verify(previousTicket, PRODUCT, closesAt)).isEmpty();
        }

        @Test
        void 발급은_현재_키로만_한다() {
            assertThat(rotating.issue(PRODUCT, CUSTOMER, ISSUED_AT)).isEqualTo(VECTOR_CURRENT);
        }
    }

    @Nested
    class 설정 {

        @Test
        void 약한_키나_잘못된_교체_설정이면_기동을_막는다() {
            assertThatThrownBy(() -> AdmissionTicket.of("short", List.of(), null))
                    .hasMessageContaining("16자 이상");
            assertThatThrownBy(() -> AdmissionTicket.of(CURRENT, List.of("short"), Instant.now()))
                    .hasMessageContaining("옛 키도");
            assertThatThrownBy(() -> AdmissionTicket.of(CURRENT, List.of(CURRENT), Instant.now()))
                    .hasMessageContaining("현재 키");
            assertThatThrownBy(() -> AdmissionTicket.of(CURRENT, List.of(PREVIOUS), null))
                    .hasMessageContaining("끝나는 때");
            assertThatThrownBy(() -> AdmissionTicket.of(CURRENT,
                    List.of(PREVIOUS, PREVIOUS + "-2", PREVIOUS + "-3"), Instant.now()))
                    .hasMessageContaining("2개까지");
        }

        @Test
        void 창이_수명보다_길면_기동을_막는다() {
            assertThatThrownBy(() -> SignedToken.of("et_", 30, 120, CURRENT, List.of(), null))
                    .hasMessageContaining("창 <= 수명");
        }
    }
}
