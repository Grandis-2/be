package com.grandis.nova.batch.reconciliation.mock;

/**
 * Mock 원장 목록을 끝까지 받지 못했다. 이 실행은 판정 불가로 끝난다 — 받지 못한 행 때문에 "Mock 에 없음" 이 잘못 잡히기 때문이다.
 * 메시지는 실행의 실패 사유로 화면에 그대로 나간다. 예: "Mock 목록 조회 실패(3페이지, 응답을 받지 못함 — 시간 초과)"
 */
public class MockLedgerUnavailable extends RuntimeException {

    private final int page;

    MockLedgerUnavailable(int page, String reason) {
        super("Mock 목록 조회 실패(" + page + "페이지, " + reason + ")");
        this.page = page;
    }

    MockLedgerUnavailable(int page, String reason, Throwable cause) {
        super("Mock 목록 조회 실패(" + page + "페이지, " + reason + ")", cause);
        this.page = page;
    }

    public int getPage() {
        return page;
    }
}
