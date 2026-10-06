package com.grandis.nova.member.customer;

/**
 * GET/PUT /me/profile 응답 data. `displayName` 은 카카오에서 받은 표시 이름이라 읽기 전용이다 — 화면이 "누구의 정보인지" 를
 * 한 번 더 조회하지 않아도 되게 같이 준다. 나머지 셋은 회원이 입력한 값이고 안 채웠으면 null 이다.
 */
public record ProfileResponse(String displayName, String name, String email, String phoneNumber) {

    static ProfileResponse of(String displayName, Profile profile) {
        return new ProfileResponse(displayName, profile.name(), profile.email(), profile.phoneNumber());
    }
}
