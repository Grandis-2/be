# 관리자 알림 이벤트 계약 (preorder · order → 허브)

관리자 화면의 알림창(관리자용 · 개발자용 탭)을 만드는 메시지다. 일이 일어난 것을 아는 서비스(preorder · order)가 보내고 허브(member 예정)가 받아 저장한다. 허브는 같은 알림을 한 건으로 묶어 숫자만 갱신하고, 문제가 풀리면 해소로 남긴다. 서버 상태를 보는 알림(5xx 비율 · 대기열 · 배송 대기 주문 수 등)은 이 메시지가 아니라 Grafana 경보 규칙이 지표로 판정해 허브에 넣는다 — 이 문서에는 그 규칙이 쓰는 order 의 지표만 적는다(아래 "지표"). 문서와 어긋나는 쪽이 고친다.

보내는 쪽은 원장을 바꾸는 트랜잭션에서 자기 아웃박스에 `ADMIN_ALERT_REQUESTED` 를 적고, 커밋 뒤 SQS 로 보낸다. 알림은 업무를 되돌리지 않는다 — 허브가 내려가 있어도 메시지는 큐에서 기다린다.

## 전달

| 이벤트 | 큐 | 보내는 쪽 | 받는 쪽 |
| --- | --- | --- | --- |
| `ADMIN_ALERT_REQUESTED` | `admin-alert` | preorder, order | 허브(member 예정) |

- **최소 한 번.** 커밋 직후 한 번 보내고, 못 보냈으면 릴레이가 다시 보낸다. 허브는 `eventId` 로 중복을 막는다.
- **순서 보장 없음.** 허브는 `occurredAt` 으로 순서를 판단한다.
- **메시지 속성.** `eventType` · `eventId`(문자열). 다른 이벤트와 같다.
- **`notification` 큐에는 보내지 않는다.** member 의 회원 알림 소비자가 다른 eventType 을 계약 위반(`NOT_NOTIFICATION`)으로 보고 DLQ 에 넣는다.

## 봉투

다른 이벤트와 같은 `EventEnvelope` 다.

```json
{
  "eventId": "6f1c2a7e-0d3b-4c8f-9a51-2b7d8e4f0c19",
  "eventType": "ADMIN_ALERT_REQUESTED",
  "aggregateType": "DRAW_CAMPAIGN",
  "aggregateId": "0199a3f2-8a10-7b21-9c32-4d5e6f708192",
  "occurredAt": "2026-10-20T05:02:11.123456Z",
  "payload": {
    "type": "DRAW_SETTLED",
    "target": { "kind": "DRAW", "id": "0199a3f2-8a10-7b21-9c32-4d5e6f708192" },
    "metrics": {
      "winners": 1000,
      "winnerCount": 1000,
      "closesAt": "2026-10-20T04:00:00Z",
      "settledAt": "2026-10-20T05:02:11.120034Z"
    }
  }
}
```

| 칸 | 뜻 |
| --- | --- |
| `aggregateType` · `aggregateId` | 알림이 생긴 원인. 보내는 서비스의 기존 값 중 맞는 것을 쓴다. 허브는 추적에만 쓴다 |
| `occurredAt` | 사건을 알아챈 시각(UTC). 아웃박스에 적은 시각이다 |
| `payload.type` | 알림 종류. 아래 표의 값 중 하나 |
| `payload.target.kind` | `PRODUCT` 또는 `DRAW` |
| `payload.target.id` | 상품 id 또는 드로우 회차 id(UUID 문자열) |
| `payload.metrics` | 종류마다 정한 숫자 · 시각. 시각은 ISO-8601 UTC 문자열이다. 없는 값은 칸을 빼지 않고 `null` 로 둔다 |

보내는 쪽은 사실만 보낸다. 받는 사람(ADMIN · DEVELOPER), 심각도, 묶음 · 해소 규칙, 화면 문구는 허브와 프론트가 정한다. 상품 · 회차 이름도 보내지 않는다.

## 종류

| `type` | 보내는 쪽 | 언제 | `target` | `metrics` |
| --- | --- | --- | --- | --- |
| `PREORDER_OPENED` | preorder | 회차가 오픈한 뒤 한 번. 회원에게 오픈 알림을 보내는 시점(`CampaignOpeningJob`)과 같다 | `PRODUCT`, 상품 id | `campaignId`, `opensAt`, `closesAt` |
| `PREORDER_CLOSED` | preorder | 회차가 마감한 뒤 한 번 | `PRODUCT`, 상품 id | `campaignId`, `closesAt`, `acceptedCount` |
| `DRAW_SETTLED` | order | 드로우 결과를 반영한 트랜잭션에서 한 번(아래 "드로우") | `DRAW`, 회차 id | `winners`, `winnerCount`, `closesAt`, `settledAt` |
| `DRAW_DELAYED` | order | 마감 뒤 1시간이 지나도 결과를 반영하지 못한 회차를 찾을 때마다(아래 "드로우") | `DRAW`, 회차 id | `closesAt`, `drawRequestedAt`, `authorizingEntries`, `paidEntries` |

- `acceptedCount`: 그 회차에 접수된 예약 전체 수다. 마감 뒤 취소된 예약도 포함한다.
- 허브는 `PREORDER_OPENED` · `PREORDER_CLOSED` · `DRAW_SETTLED` 를 `(type, target, campaignId 또는 회차 id)` 당 한 건만 저장한다. 보내는 쪽이 재시도로 두 번 보내도 된다.
- 허브는 `DRAW_SETTLED` 를 받으면 같은 회차의 열린 `DRAW_DELAYED` 를 해소한다.

## 드로우

order 의 두 종류는 봉투의 `aggregateType` 이 `DRAW_CAMPAIGN`, `aggregateId` 가 회차 id 다(추첨 요청 `DRAW_REQUESTED` 와 같다).

- **`DRAW_SETTLED`.** 결과 수신(Mock 추첨)과 전원 당첨(결제 완료 응모가 당첨 인원 이하) 두 경로 모두 반영 표식(`settled_at`)을 적은 트랜잭션에서 회원 당첨 알림과 함께 적는다. 반영이 롤백되면(재고 반납 어긋남) 함께 사라지고, 같은 결과를 다시 받으면 표식 앞에서 돌아가 다시 적지 않는다.
  - `winners` 는 실제 당첨 수(0원 주문을 만든 응모), `winnerCount` 는 설정한 당첨 인원이다. 전원 당첨이면 `winners` 가 더 작을 수 있고, 결제 완료 응모가 없던 회차는 `winners` 가 0 이다.
  - `settledAt` 은 반영 표식의 시각, `closesAt` 은 회차의 마감 시각이다.
- **`DRAW_DELAYED`.** order 가 10분마다(`nova.draw.stall-check-interval`) 마감 뒤 1시간 ~ 1일 사이인데 반영하지 못한 회차를 찾는다. 1일이 지나면 더 보내지 않는다 — 그 뒤로는 관리자 진행 조회로 본다.
  - 같은 회차에 검사마다 다시 온다. 검사에 리더가 없어 **order 태스크 수만큼 중복될 수 있다**(같은 검사 주기에 태스크마다 한 건). 허브가 한 건으로 묶는다.
  - 회차를 잠근 뒤 반영 여부를 다시 보고 적는다 — 다른 트랜잭션(반영 · 추첨 요청 · 응모 결제 승인)이 회차를 쥐고 있어 잠글 수 없으면 그 검사에서는 건너뛴다(반영되지 않으면 다음 검사가 보낸다). 그래서 반영을 커밋한 뒤에는 그 회차의 `DRAW_DELAYED` 를 적지 않는다 — 인스턴스 사이 시계 차를 빼면 `occurredAt` 이 `DRAW_SETTLED` 보다 늦지 않다.
  - `drawRequestedAt` 은 Mock 에 추첨을 요청한 시각이다. 결제 확인 중 응모가 남아 아직 요청하지 않았으면 `null` 이다. `authorizingEntries` 는 결제 확인 중, `paidEntries` 는 결제 완료 응모 수다.

## 지표

Grafana 경보 규칙이 판정하는 관리자 알림 중 order 의 지표를 쓰는 것이다. 지표는 OTel 에이전트가 Mimir 로 보낸다.

| 알림 | 지표(Prometheus 이름) | 뜻 |
| --- | --- | --- |
| `SHIPPING_BACKLOG` | `order_status_count{status}` | 그 상태의 주문 수. `status` 는 `PREPARING_ITEMS`(상품 준비) · `READY_TO_SHIP`(출고 대기) 둘뿐이다 |

- order 가 기동하자마자 한 번, 그 뒤 30초마다(`nova.metrics.refresh-interval`) DB 에서 세어 둔다. 첫 갱신 전은 NaN 이다(0 은 "대기 없음"으로 읽힌다). 갱신에 실패하면 앞의 값을 둔다.
- **모든 order 인스턴스가 같은 값을 낸다.** 규칙은 인스턴스를 더하지 말고 `max by (status)` 로 묶은 뒤 두 상태를 더한다(preorder 의 `preorder_status_count` 와 같다). 예: `sum(max by (status) (order_status_count))`. 임계값은 Grafana 규칙이 정한다.

## 실패 처리(허브)

- JSON 이 아니거나, eventType 이 다르거나, 필수 칸이 없으면 계약 위반으로 보고 `admin-alert-dlq` 에 바로 넣는다.
- 모르는 `type` 은 저장하되 경고 로그를 남긴다. 그래서 새 종류를 추가할 때 허브를 먼저 배포하지 않아도 된다.

## 배포 순서

`admin-alert` 큐와 권한(preorder · order 보내기, 허브 받기 · DLQ 넣기)은 인프라 설정이 먼저 반영돼야 한다. 큐가 없을 때 보내는 쪽이 먼저 나가면 아웃박스가 재시도하며 실패 로그를 남긴다(유실 없음). 큐가 있으면 보내는 쪽이 허브보다 먼저 나가도 메시지는 큐에서 기다린다.
