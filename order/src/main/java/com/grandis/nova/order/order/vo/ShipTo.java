package com.grandis.nova.order.order.vo;

import java.util.Objects;

/**
 * 주문의 배송지. 주문마다 복사해 두므로 회원이 기본 배송지를 바꿔도 이미 낸 주문은 그대로다.
 * order 는 customers 를 읽지 않는다(모듈 경계) — 주문 요청으로 받는다.
 *
 * 상세 주소(line2)만 비울 수 있고, 빈 문자열은 없는 것(null)으로 맞춘다.
 * 길이는 DB 칸(ship_to_* 50/20/10/200/200)과 같다. MySQL 은 글자 수로 세므로 코드 포인트로 센다.
 *
 * 개인정보라 toString 에 값을 싣지 않는다. 이걸 품은 Order 를 로그 · 예외 메시지에 찍어도 새지 않게.
 */
public record ShipTo(String name, String phone, String postalCode, String line1, String line2) {

    public ShipTo {
        requireText(name, "name", 50);
        requireText(phone, "phone", 20);
        requireText(postalCode, "postalCode", 10);
        requireText(line1, "line1", 200);
        line2 = line2 == null || line2.isBlank() ? null : line2;
        if (line2 != null) {
            requireLength(line2, "line2", 200);
        }
    }

    @Override
    public String toString() {
        return "ShipTo[***]";
    }

    private static void requireText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 이 비어 있다");
        }
        requireLength(value, field, maxLength);
    }

    private static void requireLength(String value, String field, int maxLength) {
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException("%s 는 %d자 이하여야 한다".formatted(field, maxLength));
        }
    }
}
