package com.grandis.nova.member.auth.infrastructure.kakao;

/**
 * 카카오 `/v2/user/me` 에서 우리가 쓰는 것 전부. 검수 없이 받을 수 있는 항목만(회원번호·닉네임·프로필 사진).
 *
 * @param id              카카오 회원번호. 앱별 고유이며 바뀌지 않는다. 64비트 정수지만 customers.kakao_id 가 varchar(64) 라
 *                        문자열로 받아 그대로 쓴다 — 산술을 안 하니 숫자형으로 들 이유가 없고, 넘침 걱정도 없다
 * @param nickname        profile_nickname. customers.display_name 의 초기값. 동의 안 하면 null
 * @param profileImageUrl profile_image. 지금은 저장하지 않는다(09)
 */
public record KakaoUserInfo(String id, String nickname, String profileImageUrl) {
}
