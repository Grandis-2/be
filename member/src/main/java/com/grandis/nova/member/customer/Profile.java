package com.grandis.nova.member.customer;

/**
 * 회원이 직접 입력하는 본인 정보. 카카오가 검수 없이 주지 않는 항목이라(닉네임·프로필 사진만 준다) 화면에서 받는다.
 * 셋 다 비어 있을 수 있다 — 가입은 카카오 로그인 한 번으로 끝나고 이 값들은 그다음에 받는다.
 * 저장은 칸 단위로 선택이지만(한 칸만 채워 두는 것도 저장된다) **화면을 넘어가려면 셋이 다 있어야 한다** —
 * 그 판정이 {@link #isComplete()} 이고, 로그인 응답이 그 결과를 실어 보낸다.
 *
 * 배송지와는 다른 것이다. 배송지는 "물건을 받을 곳"(받는 사람이 회원이 아닐 수 있다)이고 이쪽은 "회원 본인"이다.
 * 그래서 칸이 겹쳐도 합치지 않는다.
 */
public record Profile(String name, String email, String phoneNumber) {

    public static final Profile EMPTY = new Profile(null, null, null);

    /**
     * 셋이 다 채워졌나. 빈 문자열은 요청 경계에서 null 이 되므로(ProfileRequest) 여기서는 null 만 본다.
     *
     * 셋을 다 요구하는 이유: 주문·배송에 이름과 연락처가 필요하고, 이메일은 주문 확인을 보낼 유일한 수단이다.
     * 하나라도 선택으로 두면 그 회원은 결제 직전에 다시 붙잡아야 한다.
     */
    public boolean isComplete() {
        return name != null && email != null && phoneNumber != null;
    }
}
