package com.grandis.nova.payment.client.toss;

/**
 * 취소 오류 코드 판정 중 이 패키지 밖(환불 복구)이 쓰는 것. 분류표({@link TossErrorCatalog}) 자체는 이 패키지에 닫혀 있다.
 */
public final class TossCancelCodes {

    private TossCancelCodes() {
    }

    /** 멱등 키 단위: 이 키의 첫 요청이 아직 처리 중이다(409). 같은 키로 다시 보내면 그 요청의 결과를 받는다. */
    public static boolean isSameKeyInFlight(String code) {
        return TossConfirmCodes.IDEMPOTENT_REQUEST_PROCESSING.equals(code);
    }

    /** 취소 실패 확정 코드(분류표). 복구가 받은 거절을 다음 회차로 넘길 때 행의 오류 코드로 알아본다. */
    public static boolean isRejection(String code) {
        return TossErrorCatalog.cancel(code).filter(TossErrorCatalog.Command.REJECTED::equals).isPresent();
    }
}
