package com.grandis.nova.payment.recovery;

import com.grandis.nova.payment.domain.enums.TargetType;
import com.grandis.nova.payment.domain.enums.TransactionType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 복구 작업이 내린 결론 가운데 사람이 봐야 하는 것(payment.recovery.attention, 유형 · 대상 · 사유별). 경보는 이 증가에 건다.
 * <ul>
 *   <li>에스컬레이션: 거래를 상태 그대로 두고 다시 집지 않는다 — 대상(주문 · 응모)은 결제 확인 중 · 취소 중에 멈춘다.</li>
 *   <li>환불 거절 확정: 실패가 대상에 통지되지만, 같은 대상의 환불은 다시 열리지 않는다 — 돈은 돌아가지 않은 채다.</li>
 * </ul>
 * 범위 밖: 환불 요청을 받았는데 성공한 결제가 없는 경우는 메시지가 DLQ 로 가 DLQ 경보가 잡고, 마지막 환불이 거절돼 다시 열지 않는 요청은 그
 * 거절을 셀 때 이미 셌다. 처리기가 같은 거래에서 예외를 되풀이하거나 상점 설정 오류로 호출이 계속 실패하면 바로 세지 않고, 거래마다
 * 상한(승인 1시간 · 환불 24시간)에 이르러 TIMED_OUT 으로 센다.
 *
 * 반영이 커밋되고 ERROR 로그를 남긴 뒤에 한 번 센다 — 리스를 잃은 쪽은 예외로 빠지므로 두 작업자가 함께 세지 않는다. 그 한 건이 경보에서
 * 빠지는 때가 둘 있다: 로그와 셈 사이에 프로세스가 멈췄을 때, 그리고 태스크가 뜬 뒤 첫 지표 전송 전에 셌을 때(Mimir 의 첫 표본이 이미 1이라
 * increase() 가 0 이다). 둘 다 ERROR 로그는 남는다. 커밋과 로그 사이에 멈추면 둘 다 남지 않는다 — 그 거래는 상태로만 남는다. 사유는 아래 네
 * 가지로 접는다 — 오류 코드(토스 코드 · 조회 상태)는 값이 정해져 있지 않고, 원문은 그 ERROR 로그에 있다. 낼 수 있는 쌍을 처음부터 0 으로
 * 등록한다 — 첫 증가 전에 시계열이 없으면 increase() 가 그 첫 번을 놓친다.
 */
@Component
public class RecoveryAttention {

    static final String METER = "payment.recovery.attention";

    /** 사람이 봐야 하는 까닭. 태그 값은 이름을 바꾸면 경보 · 대시보드가 끊기므로 고정한다. */
    public enum Reason {
        /** 첫 전송부터 상한(승인 1시간 · 환불 24시간)이 지나도 확정하지 못했다. */
        TIMED_OUT,
        /** 결제사 응답이 우리 기록과 맞지 않는다(금액 · 승인 시각 · 주문 번호 · 전액 취소 · 취소 시각). */
        RESULT_MISMATCH,
        /** 조회 상태가 자동으로 확정하지 않는 값이거나, 환불할 결제가 결제사에 없다. */
        UNEXPECTED_STATE,
        /** 결제사가 환불을 거절해 실패로 확정했다. */
        REFUND_REJECTED
    }

    private final MeterRegistry registry;

    public RecoveryAttention(MeterRegistry registry) {
        this.registry = registry;
        for (TransactionType type : TransactionType.values()) {
            for (TargetType target : TargetType.values()) {
                for (Reason reason : Reason.values()) {
                    if (canOccur(type, target, reason)) {
                        counter(type, target, reason);
                    }
                }
            }
        }
    }

    public void record(TransactionType type, TargetType target, Reason reason) {
        counter(type, target, reason).increment();
    }

    /** 환불 거절은 환불에만 있고, 응모는 환불하지 않는다(낙첨 응모비도 돌려주지 않는다 — RefundSettlement). */
    static boolean canOccur(TransactionType type, TargetType target, Reason reason) {
        if (type == TransactionType.REFUND) {
            return target == TargetType.ORDER;
        }
        return reason != Reason.REFUND_REJECTED;
    }

    private Counter counter(TransactionType type, TargetType target, Reason reason) {
        return Counter.builder(METER)
                .description("복구가 내린 결론 가운데 사람이 봐야 하는 것")
                .tag("type", type.name())
                .tag("target", target.name())
                .tag("reason", reason.name())
                .register(registry);
    }
}
