# member 내부 API 계약 — 회원 이름

양쪽 구현의 기준이다. member 가 응답을 만들고(`InternalCustomerController`), 부르는 서비스가 소비한다. 문서와 어긋나는 쪽이 고친다.

| 경로 | 부르는 쪽 | 토큰 |
|---|---|---|
| `GET /internal/customers/me` | catalog `MemberClient` · `ReviewService` (리뷰 작성자 이름) | 회원(USER) |
| `GET /internal/customers/names` | order · preorder `MemberClient` · `MemberNames` (관리자 목록 · 상세의 회원 이름) | 관리자(ADMIN) |

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

## `GET /internal/customers/names?ids=` — 회원 이름 일괄 조회 (내부 전용)

**왜.** 관리자 예약 · 주문 목록과 상세는 회원 id(`customerId`)만 들고 있다. 이름은 member 의 `customers` 에 있고 preorder · order 는 그 표를 읽지 않는다. 화면이 이름을 바로 받도록 preorder · order 가 이 API 로 받아 `customerName` 을 채운다. 가림 규칙을 member 한 곳에서 정하므로 두 서비스의 결과가 같다.

**응답 · 가림은 관리자 API 와 같다.** `GET /api/v1/admin/members?ids=`(`AdminCustomerController`)와 같은 서비스 메서드(`CustomerService.names`)를 쓴다.

- `ids` 1~100개(반복 · 쉼표 모두). 100 은 관리자 목록 한 쪽의 최대 크기(order · preorder `PageSizes.MAX`)라 한 쪽이 한 번에 든다. 0개 · 101개는 400.
- 요청 순서, 같은 id 는 한 번, **없는 회원은 빠진다**(탈퇴가 없어 지금은 생기지 않는다). 받는 쪽은 빠진 회원의 이름을 `null` 로 둔다.
- `name` 은 실명(없거나 공백뿐이면 카카오 표시 이름)의 첫 글자 + `**` 다(김철수 · 김철 → 김**).
- member 는 이름 칸(id · 실명 · 표시명)만 읽는 문장 하나로 답한다(트랜잭션 없음).

**인증.** 관리자 요청의 JWT 를 그대로 싣는다. **ADMIN 만** — 회원 토큰은 403, 토큰 없음은 401. 회원 토큰으로 남의 이름을 물을 길을 만들지 않는다.

**응답 200.**

```json
{
  "success": true,
  "data": { "items": [ { "memberId": "0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c8d", "name": "김**" } ] }
}
```

**받는 쪽(preorder · order `MemberNames`).** 이름은 표시용이라 못 받아도 목록 · 상세는 그대로 200 이고 `customerName` 만 `null` 이다.

- 한 화면에 member 호출은 많아야 한 번 — 같은 id 는 한 번, 캐시에 없는 id 만 묶어 묻는다.
- 받은 이름은 10분 캐시한다(없는 회원도 "없음" 으로). 이름을 바꾼 회원은 길어야 10분 뒤 바뀐다. 실패는 캐시하지 않는다.
- 연결 300ms · 읽기 500ms. 재시도하지 않는다. 서킷 브레이커(10초 열림)와 동시 호출 상한(20, 기다리지 않음)을 거친다 — 회로가 열려 있으면 부르지 않는다.
- 4xx · 5xx · 시간 초과 · 읽을 수 없는 응답 모두 이름 없이 응답하고 경고 로그를 남긴다.
- 호출은 DB 트랜잭션 밖(컨트롤러)에서 한다.

부르는 서비스의 인프라 `calls` 에 `spring.http.serviceclient.member.base-url: member` 가 있어야 한다. 없으면 이름이 늘 `null` 이다.
