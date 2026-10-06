package com.grandis.nova.member.customer;

import jakarta.validation.constraints.NotBlank;
import java.text.Normalizer;

/**
 * PUT /me/default-address 본문. api-spec: 수령인 1~50 · 전화 1~20 · 우편번호 1~10 · 주소 1~200 필수, 상세주소 최대 200 선택/null.
 * 길이는 DDL 의 varchar 와 같고, 코드포인트로 센다(@CodePointSize — @Size 는 char 를 세서 이모지가 두 배로 잡힌다). 다섯 칸이 한 덩어리다 —
 * 넷 중 하나가 빠지면 DB 에 가기 전에 400 VALIDATION_FAILED.
 *
 * 값은 생성자에서 NFC 로 정규화하고 앞뒤 공백을 지운 뒤에 검증한다(요청 경계 한 곳). 한글 음절은 NFD 면 자모 2~3 코드포인트라 "한국어이름" 이
 * NFC 5 / NFD 13 이다 — MySQL utf8mb4 도 코드포인트로 세므로 NFD 입력은 varchar(50) 에 16자밖에 안 들어가고 사용자는 17자를 쳤는데 400 을 받는다.
 * macOS·iOS 붙여넣기에 NFD 가 섞여 온다. 정규화 뒤에는 검증·DB·사용자 인식이 같은 수를 센다.
 */
public record DefaultAddressRequest(
        @NotBlank @CodePointSize(max = 50) String name,
        @NotBlank @CodePointSize(max = 20) String phone,
        @NotBlank @CodePointSize(max = 10) String postalCode,
        @NotBlank @CodePointSize(max = 200) String line1,
        @CodePointSize(max = 200) String line2
) {
    public DefaultAddressRequest {
        name = clean(name);
        phone = clean(phone);
        postalCode = clean(postalCode);
        line1 = clean(line1);
        line2 = clean(line2);
    }

    /** NFC 정규화 + 앞뒤 공백 제거. null 은 null 로(필수 여부는 @NotBlank 몫). */
    static String clean(String s) {
        return s == null ? null : Normalizer.normalize(s, Normalizer.Form.NFC).strip();
    }

    /** 상세주소가 비면 null 로. 나머지는 생성자에서 이미 정리됐다. */
    public ShippingAddress toAddress() {
        String detail = line2 == null || line2.isEmpty() ? null : line2;
        return new ShippingAddress(name, phone, postalCode, line1, detail);
    }
}
