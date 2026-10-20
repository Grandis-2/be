package com.grandis.nova.batch.reconciliation.mock;

/**
 * Mock 원장 목록을 끝까지 받지 못했다. 이 실행은 판정 불가로 끝난다 — 받지 못한 행 때문에 "Mock 에 없음" 이 잘못 잡히기 때문이다.
 * 메시지는 실행의 실패 사유로 화면에 그대로 나간다. 예: "Mock 목록 조회 실패(3페이지, 응답을 받지 못함 — 시간 초과 · 3번 시도)"
 */
public class MockLedgerUnavailable extends RuntimeException {

    private final int page;
    private final String reason;
    private final boolean retryable;

    MockLedgerUnavailable(int page, String reason) {
        this(page, reason, null, false);
    }

    MockLedgerUnavailable(int page, String reason, Throwable cause) {
        this(page, reason, cause, false);
    }

    private MockLedgerUnavailable(int page, String reason, Throwable cause, boolean retryable) {
        super("Mock 목록 조회 실패(" + page + "페이지, " + reason + ")", cause);
        this.page = page;
        this.reason = reason;
        this.retryable = retryable;
    }

    /** 응답을 받지 못했거나 5xx — 일시 실패일 수 있어 같은 페이지를 다시 받아 볼 만하다 */
    static MockLedgerUnavailable retryable(int page, String reason, Throwable cause) {
        return new MockLedgerUnavailable(page, reason, cause, true);
    }

    /** 다시 받아도 끝내 못 받았다 — 사유에 시도 횟수를 붙인다 */
    MockLedgerUnavailable afterAttempts(int attempts) {
        return new MockLedgerUnavailable(page, reason + " · " + attempts + "번 시도", this, false);
    }

    public int getPage() {
        return page;
    }

    boolean isRetryable() {
        return retryable;
    }
}
