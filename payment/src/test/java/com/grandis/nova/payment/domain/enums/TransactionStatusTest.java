package com.grandis.nova.payment.domain.enums;

import com.grandis.nova.payment.domain.model.Outcome;
import com.grandis.nova.payment.vo.ProviderError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.grandis.nova.payment.domain.enums.TransactionStatus.FAILED;
import static com.grandis.nova.payment.domain.enums.TransactionStatus.PENDING;
import static com.grandis.nova.payment.domain.enums.TransactionStatus.PROCESSING;
import static com.grandis.nova.payment.domain.enums.TransactionStatus.RETRY_SCHEDULED;
import static com.grandis.nova.payment.domain.enums.TransactionStatus.SUCCEEDED;
import static com.grandis.nova.payment.domain.enums.TransactionType.CAPTURE;
import static com.grandis.nova.payment.domain.enums.TransactionType.REFUND;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상태 머신 표 전체. 기대값은 코드에서 끌어오지 않고 여기 표로 적는다 — 표에 없는 칸은 모두 "받지 않음" 이다.
 * 새 상태 · 유형 · 사건이 생기면 전 조합을 돌리는 테스트가 빠진 칸을 드러낸다.
 */
class TransactionStatusTest {

    static final ProviderError ERROR = new ProviderError("PROVIDER_ERROR", "일시 오류");

    static final List<Outcome> OUTCOMES = List.of(
            new Outcome.Confirmed(Instant.parse("2026-09-29T00:00:00Z")),
            new Outcome.Rejected(ERROR),
            new Outcome.InProgress(ERROR, Duration.ofSeconds(30)),
            new Outcome.Unknown(ERROR));

    /** 결제창 승인 요청이 CAPTURE 를 시작한다. REFUND 는 사용자가 시작하지 않는다. */
    static final Map<TransactionStatus, Map<TransactionType, TransactionStatus>> START = Map.of(
            PENDING, Map.of(CAPTURE, PROCESSING));

    /**
     * 워커 선점. CAPTURE PENDING 은 워커가 절대 집지 않는다 — 사용자가 결제창을 버렸을 수 있다(ERD).
     * PROCESSING 은 리스가 만료된 것만(시각 조건은 저장소가 DB 시각으로 본다).
     */
    static final Map<TransactionStatus, Map<TransactionType, TransactionStatus>> CLAIM = Map.of(
            PENDING, Map.of(REFUND, PROCESSING),
            RETRY_SCHEDULED, Map.of(CAPTURE, PROCESSING, REFUND, PROCESSING),
            PROCESSING, Map.of(CAPTURE, PROCESSING, REFUND, PROCESSING));

    /** 반영은 PROCESSING 에서만. 유형과 상관없이 같다. */
    static final Map<Class<? extends Outcome>, TransactionStatus> RESOLVE_FROM_PROCESSING = Map.of(
            Outcome.Confirmed.class, SUCCEEDED,
            Outcome.Rejected.class, FAILED,
            Outcome.InProgress.class, RETRY_SCHEDULED,
            Outcome.Unknown.class, PROCESSING);

    static Stream<Arguments> statusAndType() {
        List<Arguments> all = new ArrayList<>();
        for (TransactionStatus status : TransactionStatus.values()) {
            for (TransactionType type : TransactionType.values()) {
                all.add(Arguments.of(status, type));
            }
        }
        return all.stream();
    }

    static Stream<Arguments> statusAndOutcome() {
        List<Arguments> all = new ArrayList<>();
        for (TransactionStatus status : TransactionStatus.values()) {
            for (Outcome outcome : OUTCOMES) {
                all.add(Arguments.of(status, outcome));
            }
        }
        return all.stream();
    }

    @ParameterizedTest(name = "start {0} {1}")
    @MethodSource("statusAndType")
    void start(TransactionStatus from, TransactionType type) {
        assertThat(from.start(type)).isEqualTo(expected(START, from, type));
    }

    @ParameterizedTest(name = "claim {0} {1}")
    @MethodSource("statusAndType")
    void claim(TransactionStatus from, TransactionType type) {
        assertThat(from.claim(type)).isEqualTo(expected(CLAIM, from, type));
    }

    @ParameterizedTest(name = "resolve {0} {1}")
    @MethodSource("statusAndOutcome")
    void resolve(TransactionStatus from, Outcome outcome) {
        Optional<TransactionStatus> expected = from == PROCESSING
                ? Optional.of(RESOLVE_FROM_PROCESSING.get(outcome.getClass()))
                : Optional.empty();

        assertThat(from.resolve(outcome)).isEqualTo(expected);
    }

    // 확정 실패만 FAILED 로 간다. 결과 불명 · 처리 중이 FAILED 가 되면 응답을 잃은 성공 결제를 실패로 단정한다(이중 청구).
    @Test
    void onlyRejectedEndsInFailed() {
        assertThat(OUTCOMES.stream().filter(o -> PROCESSING.resolve(o).orElseThrow() == FAILED))
                .singleElement().isInstanceOf(Outcome.Rejected.class);
    }

    @Test
    void finishedStatusesAreTerminal() {
        assertThat(TransactionStatus.values()).filteredOn(TransactionStatus::isFinished)
                .containsExactlyInAnyOrder(SUCCEEDED, FAILED);
        for (TransactionStatus finished : List.of(SUCCEEDED, FAILED)) {
            for (TransactionType type : TransactionType.values()) {
                assertThat(finished.start(type)).isEmpty();
                assertThat(finished.claim(type)).isEmpty();
            }
            OUTCOMES.forEach(o -> assertThat(finished.resolve(o)).isEmpty());
        }
    }

    private static Optional<TransactionStatus> expected(
            Map<TransactionStatus, Map<TransactionType, TransactionStatus>> table, TransactionStatus from,
            TransactionType type) {
        return Optional.ofNullable(table.getOrDefault(from, Map.of()).get(type));
    }
}
