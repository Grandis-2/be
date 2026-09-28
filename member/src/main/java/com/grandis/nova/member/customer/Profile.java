package com.grandis.nova.member.customer;

/**
 * 회원이 직접 입력하는 본인 정보. 카카오가 검수 없이 주지 않는 항목이라(닉네임·프로필 사진만 준다) 화면에서 받는다.
 * 셋 다 선택이고 비어 있을 수 있다 — 가입은 카카오 로그인 한 번으로 끝나고 이 값들은 나중에 채운다.
 *
 * 배송지와는 다른 것이다. 배송지는 "물건을 받을 곳"(받는 사람이 회원이 아닐 수 있다)이고 이쪽은 "회원 본인"이다.
 * 그래서 칸이 겹쳐도 합치지 않는다.
 */
public record Profile(String name, String email, String phoneNumber) {

    public static final Profile EMPTY = new Profile(null, null, null);
}
