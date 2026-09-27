package com.grandis.nova.member.customer;

/**
 * 기본 배송지 한 덩어리. DDL 의 ck_customer_default_address 가 "다섯 칸 전부 NULL" 또는 "line2 를 뺀 넷이 전부 NOT NULL" 만 허용하므로
 * 칸 하나씩이 아니라 이 단위로만 읽고 쓴다(api-spec F-X-01 끝). line2 만 비울 수 있다.
 */
public record ShippingAddress(String name, String phone, String postalCode, String line1, String line2) {

    public ShippingAddress {
        if (name == null || phone == null || postalCode == null || line1 == null) {
            throw new IllegalArgumentException("name, phone, postalCode, line1 are all required");
        }
    }
}
