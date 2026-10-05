# preorder ↔ catalog 내부 API 계약

양쪽 구현의 기준이다. catalog 가 응답을 만들고(`InternalProductController` · `ProductOptionsQueryService`), preorder 의 `CatalogClient` · `ProductCatalog` · `CatalogReader` 가 소비한다. 문서와 어긋나는 쪽이 고친다.

## 1.3 `GET /internal/products/{productId}/options` — 상품과 옵션 (내부 전용)

**노출.** 호출자 JWT와 Service Connect TLS를 사용한다. TLS의 트래픽 암호화만으로 호출 서비스가 preorder라는 인가까지 보장하지 않는다. `/internal/**`는 공개 라우팅에서 차단하고 내부 수신 포트의 접근 주체를 제한한다. 보안 그룹은 URL 경로를 구분하지 못하므로 catalog 공개 API와 공유하는 포트에 preorder 전용 규칙을 적용한다고 가정하지 않는다. 코드 머지는 병행할 수 있으나, 실제 접근 경로와 차단 시험을 확인한 뒤 운영 활성화한다. USER 토큰으로 비공개 상품까지 반환하므로 이 차단은 운영 필수 조건이다.

**호출 주체.**

| 언제 | 누가 | 실린 JWT | 목적 |
| --- | --- | --- | --- |
| 관리자 회차 · 차수 수정(`PUT /admin/products/{id}/preorder-campaign` 등, 등록 뒤 오픈 전) | preorder `PreorderCampaignAdminService.requirePreorderProduct` | 관리자(ADMIN) | 회차를 붙일 상품이 사전예약 상품인지 확인 |
| 사용자 접수 (`PreorderAcceptTransaction`) | preorder `CatalogReader`(캐시 경유) | 회원(USER) | 접수 가능 여부 판정과 옵션 스냅샷 복사 |

**인증.** 호출자의 JWT(`Authorization: Bearer {accessToken}`, 2026-09-28 — 이전 `X-Session-Token`)를 그대로 전달한다. catalog 는 `common:security` 필터로 서명·만료·`aud`·역할·폐기를 검증하고(NV-139 채택) **이 읽기 엔드포인트는 USER·ADMIN 둘 다 허용**한다(앞으로 생길 `/internal/**` 쓰기 엔드포인트는 ADMIN 만). 업무 판정(접수 가능한가)은 catalog 가 아니라 preorder 가 한다. TLS는 전송 암호화 역할이며 호출 서비스의 접근 제한은 위 노출 조건으로 검증한다. preorder 쪽은 `CatalogClient` 호출에 헤더를 전파하는 인터셉터를 둔다(`TokenRelayConfig` · `common:security` 의 `BearerTokenRelayInterceptor`, NV-277).

**요청.** 본문 없음. 헤더 `Authorization: Bearer {accessToken}` 필수, `X-Request-Id`는 있으면 이어 쓴다. 토큰은 요청 단위로 전달하고 캐시나 공용 인터셉터의 가변 필드에 보관하지 않는다.

**응답 200.** 봉투는 `common:web` 의 `ApiResponse` 그대로.

```json
{
  "success": true,
  "data": {
    "productId": 12,
    "title": "스마트폰 A",
    "saleMode": "PREORDER",
    "status": "ACTIVE",
    "visible": false,
    "registrationCompleted": false,
    "options": [
      { "optionId": 101, "sku": "BLACK-256", "title": "블랙 / 256GB", "price": 1200000, "status": "ACTIVE" },
      { "optionId": 102, "sku": "BLACK-512", "title": "블랙 / 512GB", "price": 1400000, "status": "PAUSED" }
    ]
  },
  "error": null,
  "timestamp": "2026-09-25T06:00:00Z",
  "traceId": "…"
}
```

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `productId` | number | `products.id` |
| `title` | string | 상품명. 스냅샷 원본 |
| `saleMode` | `PREORDER` \| `IN_STOCK` | `products.sale_mode` |
| `status` | `ACTIVE` \| `PAUSED` | `products.status`. PAUSED 는 판매 중지 — 소비자는 신규 접수 · 주문을 막는다. 사전예약 상품은 오픈 3분 전부터 이 칸이 바뀌지 않는다(2026-10-04 결정). 오픈 전에 PAUSED 로 둔 채 오픈을 넘긴 상품도 판매 중지일 뿐 회차 취소가 아니다 — 회차 취소는 이 칸으로 알리지 않고 `PREORDER_CAMPAIGN_CANCELED` 이벤트로 따로 알린다(contracts/catalog-events.md — 오픈 뒤 관리자 판매 중지 · 사유) |
| `visible` | boolean | 공개 여부 — 관리자가 등록 때 고른 값이 그대로 들어간다(2026-10-02 이벤트 방식 전환부터). 준비 전 상품도 true 일 수 있으므로 소비자는 visible 로 준비를 추론하지 말고 **두 칸을 모두 본다** |
| `registrationCompleted` | boolean | 판매 방식별 준비 — 등록 이벤트를 받은 서비스가 행을 만들었는가. 사전예약은 `preorder_campaigns` 에 그 상품 행이 있다, 일반은 `option_inventories` 에 그 상품 옵션의 행이 있다(contracts/catalog-events.md) |

`visible` · `status` · `registrationCompleted` 는 **한 문장**으로 읽어 같은 스냅샷에서 나온다(`ProductListingQueryRepository.findExposure`). 둘은 서로 다른 트랜잭션(관리자 공개 전환 · preorder/order 의 등록 이벤트 처리)이 바꾸지만, READ COMMITTED 에서 따로 읽으면 두 문장 사이에 둘 다 커밋돼 한순간도 없던 조합(공개 · 준비)이 나온다. 접수가 이 셋으로 판정하므로 한 문장이어야 한다.
| `options[].optionId` | number | `product_options.id` |
| `options[].sku` | string | 모델 안 유일 |
| `options[].title` | string | 옵션 표시명. 스냅샷 원본 |
| `options[].price` | number(정수 원) | 최종가. 스냅샷 원본 |
| `options[].status` | `ACTIVE` \| `PAUSED` | 옵션 판매 상태 |

**비공개·미완료 상품도 200 으로 준다.** 숨기는 판정은 목적별로 호출자가 한다(아래). 이 API 가 먼저 숨기면 관리자 등록 흐름이 자기 상품을 못 본다.

**오류.**

| 상태 | `error.code` | 언제 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` | 토큰 없음·만료·폐기·`Authorization` 헤더 모양 이상. 이 봉투는 common:security 의 진입점이 준다(NV-139 부터). **이 경로는 폐기 조회가 실패해도 열린다**(폐기 조회가 실패하면 닫히는 경로에 들지 않는다 — 실측: Redis 를 끊고 USER 토큰 → 200) — 그래서 여기서는 `details.retryable = true` 인 401 이 나오지 않는다. preorder 가 그 갈래를 만들 필요가 없다 |
| 403 | `FORBIDDEN` | 허용 역할(USER · ADMIN) 밖. catalog 는 `hasAnyRole(USER, ADMIN)` 으로 막는다 |
| 404 | `PRODUCT_NOT_FOUND` | 상품 없음. preorder 는 **이 코드일 때만** 빈 결과로 바꾼다. 틀린 경로의 404 는 공통 `NOT_FOUND` 로 오므로 그건 연동 오류다(지금 `CatalogReader` 는 코드를 안 보고 404 를 전부 빈 결과로 캐시한다 — 아래 남은 일). 옵션이 없는 상품은 404 가 아니라 `options: []` |
| 400 · 405 · 500 | `VALIDATION_FAILED` · `METHOD_NOT_ALLOWED` · `INTERNAL_ERROR` | 공통 처리기의 봉투. productId 가 숫자가 아님 · 허용되지 않은 메서드 · 서버 오류(원문은 싣지 않는다) |

preorder `CatalogReader` 는 401 을 사용자 토큰 문제(401)로, 404 외 나머지 4xx(403 포함)는 연동 오류(500)로 처리한다(NV-277). 401·403은 인증·권한 실패다(이 경로에는 `retryable` 401 이 없다 — 위 표). 오류를 상품 없음으로 캐시하지 않는다.

**호출 목적별 판정 조건 — preorder 가 적용한다.**

| 목적 | 조건 |
| --- | --- |
| 회차·차수 수정 | `saleMode = PREORDER`. **`status`·`visible`·`registrationCompleted` 는 보지 않는다** — 회차 행이 아직 없는 상품(등록 이벤트를 DLQ 에서 고치는 경우)도 설정할 수 있어야 한다. 등록 때의 회차 · 차수는 이 API 가 아니라 `PREORDER_PRODUCT_REGISTERED` 이벤트로 만든다 |
| 사용자 접수 | `saleMode = PREORDER AND status = ACTIVE AND visible AND registrationCompleted`, 그리고 선택한 `optionId` 가 이 상품의 `options` 에 있고 `options[].status = ACTIVE` |

preorder 는 dev(NV-281)에서 판정을 갈랐다 — 회차 설정은 `ProductCatalog.isPreorderProduct()`(`saleMode` 만), 접수는 `isOnPreorderSale()`(네 조건). preorder 는 두 칸을 `Boolean` 래퍼로 받아 칸이 빠지거나 null 이면 숨기지 않는다. **catalog 는 두 칸을 primitive `boolean` 으로 늘 싣는다** — null 이 될 수 없고, `false` 도 빠지지 않고 실리는 것을 `InternalProductApiTest` 가 고정한다. 그래서 preorder 의 래퍼가 접수 판정을 비우지 않는다.

**캐시.** 현재 코드(dev, NV-281 뒤)의 사실: `CatalogReader` 는 값의 나이와 상관없이 가진 값을 그 조회에 바로 돌려주고, 1분이 지났으면 그 조회 요청의 보안 맥락(토큰)으로 뒤에서 한 번 다시 받기를 시작할 뿐이다. `expireAfterWrite` 30분 · 최대 1,000건이다. 값이 바뀌면 catalog 가 이벤트로 알린다 — 사전예약 상품의 관리자 수정은 `PREORDER_PRODUCT_CHANGED`, 오픈 뒤 판매 중지는 `PREORDER_CAMPAIGN_CANCELED`(`contracts/catalog-events.md`). preorder 는 받은 인스턴스가 자기 캐시를 비우고 Redis 채널로 다른 인스턴스에 알린다(`CatalogCacheInvalidation`). 그래서 수정이 접수에 닿기까지는 아웃박스 릴레이 · SQS 전달 시간만큼 늦다. 비운 뒤 첫 조회는 catalog 를 그 요청 안에서 동기로 부르고, 실패는 캐시하지 않는다 — 그 순간 catalog 가 응답하지 않으면 그 상품의 접수는 옛 값 대신 503 이다. Redis 로 알리기가 끝내 실패하거나, Redis 채널(pub/sub)은 구독이 끊긴 동안 보낸 알림을 다시 주지 않으므로 그 사이 알림을 놓친 인스턴스는 위의 다시 받기로만 따라온다 — **그 경우 응답 값이 묵을 수 있는 상한은 30분**이다(조회가 뜸하면 다시 받기가 성공해도 그 첫 조회는 옛 값으로 판정된다). TTL 로 차단 지연을 허용하는 정책은 미확정이다. 등록 직후에는 회차 행이 아직 없어 내부 조회가 `registrationCompleted=false` 를 돌려주고 preorder 는 이 값을 캐시한다. 회차 행은 preorder 가 등록 이벤트로 직접 만드는데 그때 캐시를 비우지 않으므로(`CampaignRegistrar`), 그 사이 누가 조회했으면 등록이 끝난 뒤에도 "미완료" 로 남을 수 있다 — 상한은 위와 같다. 캐시 히트에서도 요청 인증·폐기 검사는 수행한다.

**하위 호환과 배포 순서.** 지금 접수 경로가 깨지지 않게 이 순서로 간다.

1. catalog: 응답에 `visible`·`registrationCompleted` 추가(기존 칸 불변). 기존 preorder 는 모르는 칸을 무시한다. `registrationCompleted` 는 판매 방식별 준비다(위 칸 정의).
2. preorder: `ProductCatalog` DTO 에 두 칸을 받는다(NV-281, `Boolean` 래퍼). 누락 · null 을 따로 검사하지 않는다 — catalog 가 두 칸을 늘 싣는다(위 "호출 목적별 판정 조건" 아래).
3. preorder: 판정 메서드 분리 — 설정용은 `saleMode` 만, 접수용은 네 조건 + 옵션 조건.
4. preorder: 캐시 무효화 방식 적용.
5. 양쪽 배포와 캐시 갱신을 확인한 뒤 통합 등록·접수를 활성화한다. 기존 상품이 있다면 실제 등록 완료 여부를 확인해 이관하며 일괄 완료 처리하지 않는다.

## 상태

1. 인증 — 호출자 JWT 전달(위 "인증"). catalog 는 `/internal/**` 을 USER · ADMIN 만 허용하고 **common:security 필터가 토큰을 검증한다**(NV-139). preorder 쪽은 `CatalogClient` 가 보안 맥락(SecurityContext)에 있는 사용자 토큰을 `Authorization` 헤더로 실어 보낸다(NV-277). 보안 맥락이 없으면(스케줄러 · 큐 소비) 헤더 없이 가므로 401 이다.
2. `/internal/**` 공개 라우팅 차단 — 인프라에서 병행하되 운영 활성화 전 검증한다.
3. catalog 쪽: 응답 칸 · 404 · 빈 옵션 목록 · 역할 규칙 · 인증 필터까지 구현됐다. 판정 분리 · 다중 인스턴스 캐시 비우기는 preorder 에 들어갔다(NV-281). preorder 쪽 남은 일: `CampaignRegistrar` 가 회차 행을 만든 뒤 상품 캐시 비우기(`evictEverywhere`) · **`PRODUCT_NOT_FOUND` 일 때만 빈 결과로 바꾸고 그 밖의 404 는 연동 오류로 분류**.
