# catalog → member 내부 API 계약 — 리뷰 작성자 이름

양쪽 구현의 기준이다. member 가 응답을 만들고(`InternalCustomerController`), catalog 의 `MemberClient` · `ReviewService` 가 소비한다. 문서와 어긋나는 쪽이 고친다.

## `GET /internal/customers/me` — 토큰 주인의 이름 (내부 전용)

**왜.** 리뷰 카드는 가린 작성자명(예: 김**)을 보여 준다. 이름은 member 의 `customers`(실명 `name` · 카카오 닉네임 `display_name`)에 있고 catalog 는 그 표를 읽지 않는다. 토큰에는 회원 id · 역할만 있다.

**호출 주체.** catalog `ReviewService.write` — 리뷰를 쓸 때 한 번. catalog 는 실명(`name`)이 있으면 실명을, 없으면 닉네임(`displayName`)을 가려(첫 글자 + `**`) 리뷰에 저장한다(2026-10-07 결정). 회원이 이름을 바꿔도 이미 쓴 리뷰는 그대로다.

**인증.** 호출자(회원)의 JWT 를 그대로 싣는다. **USER 만** — ADMIN 토큰은 403. 경로에 회원 id 를 받지 않는다 — 토큰 주인의 정보만 돌려주므로 남의 정보를 물을 길이 없다.

**응답 200.**

```json
{
  "success": true,
  "data": { "customerId": "0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d", "displayName": "철수랑", "name": "김철수" }
}
```

- `displayName` 은 늘 있다(카카오 동의가 없으면 "카카오 회원"). `name` 은 회원이 프로필을 안 채웠으면 `null` 이다.

**오류.** 401(토큰 없음 · 만료 · 폐기, 그리고 토큰의 회원이 없을 때 — `CustomerService` 가 없는 회원을 401 로 답한다) · 403(USER 아님). catalog 는 401 을 회원에게 401 로, 그 밖의 4xx — **404 포함**(이 API 는 404 를 내지 않으니 경로 없음 · 주소 오류다) — 와 읽을 수 없는 응답 · 토큰 주인이 아닌 응답을 연동 오류(500)로, 5xx · 연결 실패 · 시간 초과를 503 으로 바꾼다. 404 를 401 로 바꾸지 않는다 — 프론트가 토큰 문제로 보고 재발급 · 로그아웃한다.

`/internal/**` 은 공개 라우팅에서 빠져야 한다.
