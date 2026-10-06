package com.grandis.nova.payment.client.toss;

import java.util.Set;

/**
 * 승인 실패 확정 코드의 갈래. 사용자에게 무엇을 안내할지 정하는 데 쓴다 — 토스 코드를 아는 곳이 여기라 이 패키지에 둔다.
 * 상점 설정 오류를 구매자 사유로 보이면 사용자가 다른 카드로 다시 해도 같은 오류가 난다.
 */
public enum TossRejectionKind {
    /** 카드 · 계좌 · 구매자 사유. */
    BUYER,
    /** 인증 세션이 없거나 만료됐다(승인 창 10분). */
    PAYMENT_SESSION_EXPIRED,
    /** 잘못된 요청 · 상점 설정 오류, 그리고 승인 실패 확정 표에 없는 코드. */
    OTHER;

    private static final Set<String> SESSION_EXPIRED = Set.of("NOT_FOUND_PAYMENT_SESSION", "NOT_FOUND_PAYMENT");
    private static final Set<String> REQUEST_ERRORS = Set.of("INVALID_REQUEST", "INVALID_IDEMPOTENCY_KEY");

    public static TossRejectionKind ofConfirm(String code) {
        if (SESSION_EXPIRED.contains(code)) {
            return PAYMENT_SESSION_EXPIRED;
        }
        if (REQUEST_ERRORS.contains(code) || TossErrorCatalog.isMerchantConfiguration(code)) {
            return OTHER;
        }
        return TossErrorCatalog.confirm(code).filter(TossErrorCatalog.Command.REJECTED::equals).isPresent()
                ? BUYER : OTHER;
    }
}
