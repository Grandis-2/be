package com.grandis.nova.preorder.web;

/**
 * Idempotency-Key 헤더 규칙(계약: 8~64자).
 *
 * 컨트롤러 파라미터에 @Size 를 붙이지 않고 여기서 본다. 파라미터에 제약을 걸면 스프링이 메서드 전체를
 * 메서드 검증으로 돌려, 본문 @Valid 위반이 필드 이름("reason") 대신 파라미터 이름("request")으로 나간다.
 */
public final class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";
    static final int MIN_LENGTH = 8;
    static final int MAX_LENGTH = 64;

    private IdempotencyKeys() {
    }

    public static String require(String key) {
        if (key.length() < MIN_LENGTH || key.length() > MAX_LENGTH) {
            throw ValidationFailures.of(HEADER, "%d~%d자여야 합니다.".formatted(MIN_LENGTH, MAX_LENGTH));
        }
        return key;
    }
}
