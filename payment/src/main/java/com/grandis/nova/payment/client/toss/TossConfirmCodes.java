package com.grandis.nova.payment.client.toss;

/**
 * 승인 오류 코드 판정 중 이 패키지 밖(복구)이 쓰는 것. 분류표({@link TossErrorCatalog}) 자체는 이 패키지에 닫혀 있다.
 *
 * 둘 다 분류는 "처리 중"이지만 단위가 다르다. 토스는 오류 응답도 멱등 키에 캐시하므로(2026-10-03 샌드박스 확인), 결제 단위 신호를 받은
 * 키로 다시 보내면 같은 오류만 돌아온다.
 */
public final class TossConfirmCodes {

    /** 멱등 키 단위: 이 키의 첫 요청이 아직 처리 중이다(409). 그 요청이 끝나면 같은 키가 그 결과를 돌려준다. */
    static final String IDEMPOTENT_REQUEST_PROCESSING = "IDEMPOTENT_REQUEST_PROCESSING";
    /** 결제 단위: 같은 결제의 다른 요청이 처리 중이다. 이 키에는 이 오류가 캐시된다. */
    static final String ALREADY_PROCESSING_REQUEST = "ALREADY_PROCESSING_REQUEST";

    private TossConfirmCodes() {
    }

    /** 같은 키로 다시 보내면 첫 요청의 결과를 받는다. */
    public static boolean isSameKeyInFlight(String code) {
        return IDEMPOTENT_REQUEST_PROCESSING.equals(code);
    }

    /** 승인 실패 확정 코드(분류표). 복구가 받은 거절을 다음 회차로 넘길 때 행의 오류 코드로 알아본다. */
    public static boolean isRejection(String code) {
        return TossErrorCatalog.confirm(code).filter(TossErrorCatalog.Command.REJECTED::equals).isPresent();
    }

    /**
     * 같은 결제의 다른 요청이 토스에서 처리 중이다 — 그 요청이 끝나기 전에 새 키로 또 보내면 승인 두 건이 겹친다. 조회로 결과를
     * 기다린다.
     */
    public static boolean isOtherRequestInFlight(String code) {
        return ALREADY_PROCESSING_REQUEST.equals(code);
    }
}
