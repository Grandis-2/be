package com.grandis.nova.preorder.integration.mock;

/**
 * Mock 을 부르지 못했거나 응답이 계약과 달랐다. 메시지는 관리자 화면(조치 메모)에 나가므로 고정 문구만 담는다 —
 * 예외 원문(연결 주소 등)과 응답 값은 로그에만 남긴다.
 */
public class ExternalMockUnavailable extends RuntimeException {

    ExternalMockUnavailable(String message) {
        super(message);
    }

    ExternalMockUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
