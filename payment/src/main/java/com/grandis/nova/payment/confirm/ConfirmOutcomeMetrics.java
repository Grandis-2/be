package com.grandis.nova.payment.confirm;

import com.grandis.nova.payment.client.toss.TossCommandResult;
import com.grandis.nova.payment.client.toss.TossConfirmCodes;
import com.grandis.nova.payment.client.toss.UnknownReason;
import com.grandis.nova.payment.domain.model.Outcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 승인 API 가 토스에 처음 보낸 승인 요청의 결과(payment.confirm.outcomes, 결과 · 코드별). 결제 실패 급증 경보는 승인이 아닌 결과의
 * 비율로 건다 — 결제사 장애는 거절이 아니라 처리 중(일시 오류) · 불명(타임아웃 · 연결 실패)으로 온다.
 *
 * 거래마다 많아야 한 번 센다. 복구({@link CaptureRecovery})는 같은 거래를 여러 번 반영하므로 그 재전송 · 조회 확정은 세지 않는다.
 * 토스 호출이 분류된 결과 대신 예외로 끝나거나 그 전에 프로세스가 멈춘 거래는 첫 전송을 복구가 하므로 빠진다(payment 의 HTTP 5xx 로 보인다).
 * 승인 요청이 닿지 않아 복구에 넘긴 거래도 빠진다({@link UnreachedConfirmMetrics} 가 센다).
 *
 * 코드는 승인이면 NONE, 아니면 거래에 남기는 오류 코드다(토스 코드, 없으면 불명 사유 이름이나 우리가 붙인 코드). 미리 등록한 코드가
 * 아니면 UNLISTED_CODE 로 묶는다 — 분류표에 없는 토스 코드는 값이 정해져 있지 않다(원문은 클라이언트의 WARN 로그에 있다).
 * 첫 전송이 낼 수 있는 쌍을 처음부터 0 으로 등록한다 — 첫 증가 전에 시계열이 없으면 increase() 가 그 첫 번을 놓친다.
 */
@Component
public class ConfirmOutcomeMetrics {

    static final String METER = "payment.confirm.outcomes";
    static final String NONE = "NONE";
    static final String UNLISTED = UnknownReason.UNLISTED_CODE.name();

    /** 승인 결과. 태그 값은 이름을 바꾸면 경보 · 대시보드가 끊기므로 고정한다. */
    enum Result { CONFIRMED, REJECTED, IN_PROGRESS, UNKNOWN }

    private final MeterRegistry registry;
    private final Map<Result, Set<String>> codes = preregistered();

    public ConfirmOutcomeMetrics(MeterRegistry registry) {
        this.registry = registry;
        codes.forEach((result, known) -> known.forEach(code -> counter(result, code)));
    }

    public void record(Outcome outcome) {
        switch (outcome) {
            case Outcome.Confirmed confirmed -> count(Result.CONFIRMED, NONE);
            case Outcome.Rejected rejected -> count(Result.REJECTED, rejected.error().code());
            case Outcome.InProgress inProgress -> count(Result.IN_PROGRESS, inProgress.error().code());
            case Outcome.Unknown unknown -> count(Result.UNKNOWN, unknown.error().code());
        }
    }

    /**
     * 첫 전송이 낼 수 있는 (결과, 코드) 쌍. 불명 사유 이름은 토스 코드가 없을 때의 코드다 — LISTED_CODE 불명은 늘 토스 코드를 실으므로
     * 그 이름은 없다. 조회로 확정한 코드(LOOKUP_*)는 복구만 내므로 없다. 실패 결과마다 UNLISTED_CODE 를 둔다.
     */
    static Map<Result, Set<String>> preregistered() {
        Map<Result, Set<String>> codes = new EnumMap<>(Result.class);
        codes.put(Result.CONFIRMED, Set.of(NONE));
        Set<String> rejected = new HashSet<>(TossConfirmCodes.listedCodes(TossCommandResult.Rejected.class));
        rejected.add(UNLISTED);
        codes.put(Result.REJECTED, Set.copyOf(rejected));
        Set<String> inProgress = new HashSet<>(TossConfirmCodes.listedCodes(TossCommandResult.Transient.class));
        inProgress.addAll(TossConfirmCodes.listedCodes(TossCommandResult.Processing.class));
        inProgress.add(UNLISTED);
        codes.put(Result.IN_PROGRESS, Set.copyOf(inProgress));
        Set<String> unknown = new HashSet<>(TossConfirmCodes.listedCodes(TossCommandResult.Unknown.class));
        Arrays.stream(UnknownReason.values()).filter(reason -> reason != UnknownReason.LISTED_CODE)
                .map(UnknownReason::name).forEach(unknown::add);
        unknown.add(TossOutcomes.AMOUNT_MISMATCH);
        unknown.add(TossOutcomes.MISSING_APPROVED_AT);
        codes.put(Result.UNKNOWN, Set.copyOf(unknown));
        return codes;
    }

    private void count(Result result, String code) {
        counter(result, codes.get(result).contains(code) ? code : UNLISTED).increment();
    }

    private Counter counter(Result result, String code) {
        return Counter.builder(METER).tag("outcome", result.name()).tag("code", code)
                .description("승인 API 가 토스에 처음 보낸 승인 요청의 결과").register(registry);
    }
}
