package com.grandis.nova.member.customer;

import jakarta.validation.constraints.Email;
import java.text.Normalizer;

/**
 * PUT /me/profile 본문. 세 칸을 **통째로** 받는다 — 빠지거나 빈 값이면 그 칸을 비운다. 부분 갱신은 없다.
 * 길이는 DDL 의 varchar 와 같고 코드포인트로 센다(@CodePointSize — @Size 는 char 를 세서 이모지가 두 배로 잡힌다).
 *
 * 값은 생성자에서 NFC 로 정규화하고 앞뒤 공백을 지운 뒤에 검증한다(요청 경계 한 곳, DefaultAddressRequest 와 같은 자리).
 * 빈 문자열은 null 로 바꾼다 — "비움"과 "안 보냄"을 DB 에서 구분할 이유가 없고, varchar 에 빈 문자열이 섞이면 조회 조건이 둘로 갈린다.
 *
 * 이메일만 형식을 본다. 이메일은 RFC 가 정한 모양이 하나뿐이라 서버가 판정할 수 있다.
 * 전화번호는 형식을 보지 않는다 — 나라·표기(하이픈·국가번호·내선)마다 다르고 우리는 이 값으로 발송하지 않는다(알림은 Mock).
 * 형식 규칙이 필요해지면 기본 배송지의 연락처와 **같이** 정한다. 한쪽만 조이면 같은 번호가 화면에 따라 되고 안 된다.
 */
public record ProfileRequest(
        @CodePointSize(max = 50) String name,
        @Email @CodePointSize(max = 255) String email,
        @CodePointSize(max = 20) String phoneNumber
) {

    public ProfileRequest {
        name = clean(name);
        email = clean(email);
        phoneNumber = clean(phoneNumber);
    }

    /** NFC 정규화 + 앞뒤 공백 제거. 비면 null. */
    static String clean(String s) {
        if (s == null) {
            return null;
        }
        String v = Normalizer.normalize(s, Normalizer.Form.NFC).strip();
        return v.isEmpty() ? null : v;
    }

    public Profile toProfile() {
        return new Profile(name, email, phoneNumber);
    }
}
