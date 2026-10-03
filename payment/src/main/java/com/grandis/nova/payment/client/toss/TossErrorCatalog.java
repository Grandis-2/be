package com.grandis.nova.payment.client.toss;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 토스 오류 코드 → 분류(D8). HTTP 상태로 가르지 않는다 — PROVIDER_ERROR 는 400 인데 일시 오류다.
 * 원문: 토스 개발자센터 "코어 API 별 에러" · "인증 및 기타 헤더 설정 › 멱등키"(2026-09-29 확인). 표에 없는 코드는 여기서 비어 있고,
 * 클라이언트가 결과 불명 + WARN 으로 다룬다(조회로 확정). 문서 표 전체 대조는 TossErrorTableTest.
 *
 * 원칙 — 실패 확정: 토스가 처리하지 않았고 다시 보내도 같다. 일시 오류: 처리하지 않았고 시간이 지나면 달라질 수 있다.
 * 처리 중: 같은 요청이 진행 중. 결과 불명: 처리됐는지 모른다 — 애매하면 여기(불명을 실패로 단정하면 이중 청구 위험).
 */
final class TossErrorCatalog {

    enum Command { REJECTED, TRANSIENT, PROCESSING, UNKNOWN }

    enum Lookup { NOT_FOUND, UNKNOWN }

    /**
     * 상점 설정 오류 — 사람이 키 · 계약을 고쳐야 풀린다. ERROR 로 남긴다. 분류는 API 마다 다르다(2026-09-29 사용자 결정):
     * 승인 = 실패 확정(사용자를 승인 창 10분 동안 붙잡지 않는다), 취소 = 일시 오류(환불이 영구 실패로 굳지 않고 고친 뒤 이어진다).
     */
    static final Set<String> MERCHANT_CONFIGURATION = Set.of(
            "INVALID_API_KEY", "UNAUTHORIZED_KEY", "INCORRECT_BASIC_AUTH_FORMAT", "FORBIDDEN_REQUEST",
            "NOT_FOUND_TERMINAL_ID", "INVALID_UNREGISTERED_SUBMALL", "NOT_REGISTERED_BUSINESS");

    private static final Map<String, Command> CONFIRM = new Table<Command>()
            .put(Command.REJECTED,
                    // 카드 · 계좌 · 구매자 사유
                    "EXCEED_MAX_CARD_INSTALLMENT_PLAN", "INVALID_REQUEST", "NOT_ALLOWED_POINT_USE", "INVALID_REJECT_CARD",
                    "BELOW_MINIMUM_AMOUNT", "INVALID_CARD_EXPIRATION", "INVALID_STOPPED_CARD",
                    "EXCEED_MAX_DAILY_PAYMENT_COUNT", "NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT",
                    "INVALID_CARD_INSTALLMENT_PLAN", "NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN", "EXCEED_MAX_PAYMENT_AMOUNT",
                    "INVALID_AUTHORIZE_AUTH", "INVALID_CARD_LOST_OR_STOLEN", "RESTRICTED_TRANSFER_ACCOUNT",
                    "INVALID_CARD_NUMBER", "EXCEED_MAX_ONE_DAY_WITHDRAW_AMOUNT", "EXCEED_MAX_ONE_TIME_WITHDRAW_AMOUNT",
                    "EXCEED_MAX_AMOUNT", "INVALID_ACCOUNT_INFO_RE_REGISTER", "EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT",
                    "REJECT_ACCOUNT_PAYMENT", "REJECT_CARD_PAYMENT", "REJECT_CARD_COMPANY", "REJECT_TOSSPAY_INVALID_ACCOUNT",
                    "EXCEED_MAX_AUTH_COUNT", "EXCEED_MAX_ONE_DAY_AMOUNT", "INVALID_PASSWORD", "FDS_ERROR",
                    // 결제 없음 · 승인 창(인증 후 10분) 만료
                    "NOT_FOUND_PAYMENT", "NOT_FOUND_PAYMENT_SESSION",
                    // 멱등 키가 300자 초과 — TossIdempotencyKey 가 막으므로 오면 우리 버그다. 처리되지 않았다
                    "INVALID_IDEMPOTENCY_KEY")
            .put(Command.REJECTED, MERCHANT_CONFIGURATION)
            .put(Command.TRANSIENT,
                    "PROVIDER_ERROR", "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING", "FAILED_INTERNAL_SYSTEM_PROCESSING",
                    // 결제 불가 시간대 · 은행 서비스 시간 외 — 시간이 지나면 풀린다(승인 창 안이면)
                    "NOT_AVAILABLE_PAYMENT", "NOT_AVAILABLE_BANK")
            .put(Command.PROCESSING, "ALREADY_PROCESSING_REQUEST", "IDEMPOTENT_REQUEST_PROCESSING")
            .put(Command.UNKNOWN,
                    // 이미 처리됨 — 성공인지 실패인지 조회해야 안다
                    "ALREADY_PROCESSED_PAYMENT",
                    "UNKNOWN_PAYMENT_ERROR",
                    // 카드사 쪽 오류 — 카드사 승인 뒤에 났을 수 있다
                    "CARD_PROCESSING_ERROR",
                    // "아직 승인되지 않은 주문번호" — 문서에 뜻이 더 없다
                    "UNAPPROVED_ORDER_ID")
            .build();

    private static final Map<String, Command> CANCEL = new Table<Command>()
            .put(Command.REJECTED,
                    "INVALID_REFUND_ACCOUNT_INFO", "EXCEED_CANCEL_AMOUNT_DISCOUNT_AMOUNT", "INVALID_REQUEST",
                    "INVALID_REFUND_ACCOUNT_NUMBER", "INVALID_BANK", "REFUND_REJECTED", "FORBIDDEN_BANK_REFUND_REQUEST",
                    "NOT_CANCELABLE_AMOUNT", "NOT_CANCELABLE_PAYMENT", "EXCEED_MAX_REFUND_DUE",
                    "NOT_ALLOWED_PARTIAL_REFUND_WAITING_DEPOSIT", "NOT_ALLOWED_PARTIAL_REFUND",
                    "NOT_CANCELABLE_PAYMENT_FOR_DORMANT_USER", "EXCEED_CANCEL_LIMIT", "NOT_FOUND_PAYMENT",
                    "INVALID_IDEMPOTENCY_KEY")
            .put(Command.TRANSIENT,
                    "PROVIDER_ERROR", "FORBIDDEN_CONSECUTIVE_REQUEST", "NOT_AVAILABLE_BANK",
                    "FAILED_INTERNAL_SYSTEM_PROCESSING", "FAILED_REFUND_PROCESS", "FAILED_METHOD_HANDLING_CANCEL",
                    "COMMON_ERROR", "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING")
            // 문서의 취소 표에 있는 것만(UNAUTHORIZED_KEY · FORBIDDEN_REQUEST · INCORRECT_BASIC_AUTH_FORMAT). 나머지 설정 코드는
            // 취소에서 온다는 문서가 없다 — 오면 표에 없는 코드(결과 불명 + WARN)로 둔다
            .put(Command.TRANSIENT, "UNAUTHORIZED_KEY", "FORBIDDEN_REQUEST", "INCORRECT_BASIC_AUTH_FORMAT")
            .put(Command.PROCESSING, "IDEMPOTENT_REQUEST_PROCESSING")
            .put(Command.UNKNOWN,
                    // 이미 취소 · 환불됨 — 조회로 CANCELED 를 확인하고 완료(NV-103)
                    "ALREADY_CANCELED_PAYMENT", "ALREADY_REFUND_PAYMENT",
                    // 잔액 불일치 · 부분 환불 실패 — 전액 취소만 보내는데 왔다. 무슨 일이 있었는지 조회해야 안다
                    "NOT_MATCHES_REFUNDABLE_AMOUNT", "FAILED_PARTIAL_REFUND")
            .build();

    private static final Map<String, Lookup> LOOKUP = new Table<Lookup>()
            .put(Lookup.NOT_FOUND, "NOT_FOUND_PAYMENT", "NOT_FOUND")
            .put(Lookup.UNKNOWN,
                    "NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN_BELOW_AMOUNT", "UNAUTHORIZED_KEY",
                    "FORBIDDEN_CONSECUTIVE_REQUEST", "INCORRECT_BASIC_AUTH_FORMAT",
                    "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING")
            .build();

    private TossErrorCatalog() {
    }

    static Optional<Command> confirm(String code) {
        return Optional.ofNullable(CONFIRM.get(code));
    }

    static Optional<Command> cancel(String code) {
        return Optional.ofNullable(CANCEL.get(code));
    }

    static Optional<Lookup> lookup(String code) {
        return Optional.ofNullable(LOOKUP.get(code));
    }

    static boolean isMerchantConfiguration(String code) {
        return MERCHANT_CONFIGURATION.contains(code);
    }

    /** 한 코드가 두 분류에 들어가면 클래스 초기화에서 실패한다 — 표를 고치다 겹쳐도 조용히 덮어쓰지 않게. */
    private static final class Table<V> {

        private final Map<String, V> entries = new HashMap<>();

        Table<V> put(V verdict, String... codes) {
            return put(verdict, Set.of(codes));
        }

        Table<V> put(V verdict, Set<String> codes) {
            for (String code : codes) {
                V previous = entries.putIfAbsent(code, verdict);
                if (previous != null) {
                    throw new IllegalStateException("토스 분류표 중복: " + code + " (" + previous + ", " + verdict + ")");
                }
            }
            return this;
        }

        Map<String, V> build() {
            return Map.copyOf(entries);
        }
    }
}
