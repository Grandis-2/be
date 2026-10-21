# 회차 증설 계획 이벤트 계약 (preorder → 인프라)

사전예약 회차의 일정과 예상 방문자 수를 인프라에 알린다. 인프라는 오픈 전에 태스크를 미리 늘리고(예약 증설), 마감 뒤 되돌린다. 문서와 어긋나는 쪽이 고친다.

## 전달

| 이벤트 | 큐(논리 이름) | 보내는 쪽 | 받는 쪽 |
| --- | --- | --- | --- |
| `PREORDER_CAMPAIGN_SCALING_PLANNED` | `scaling-events` | preorder | 인프라(예약 증설) |

- **기본 꺼짐.** `nova.campaign.scaling-notice.enabled=true` 로 켠다. 큐와 preorder 의 보내기 권한(`queues.send`)이 생긴 뒤 켠다. 꺼진 동안의 변경은 켠 뒤 보내지 않는다 — 켠 뒤 관리자 전체 재발행(`POST /api/v1/admin/preorders/campaigns/republish`)으로 마감 전 회차를 맞춘다. 재발행은 마감 전 회차만 보내므로, 한 번 켠 뒤 껐다가 다시 켤 때는 인프라가 가진 증설 예약을 먼저 지우고 재발행한다(꺼진 동안 마감한 회차의 `CLOSED` 는 다시 오지 않는다).
- **최소 한 번 · 순서 보장 없음.** 받는 쪽은 `eventId` 로 중복을 막고, 회차(`productId`)마다 `scheduleVersion` 이 지금 아는 것보다 클 때만 반영한다.
- **보내는 때.** 대기열용 `PREORDER_CAMPAIGN_CHANGED` 와 같은 때 · 같은 일정 번호다 — 생성 · 일정 변경(예상 방문자 수만 바꾼 것 포함) · 마감 · 다시 열기 · 공개 여부 · 재발행.
- **메시지 속성.** `eventType` · `eventId`(문자열). 다른 이벤트와 같다.

## 봉투

다른 이벤트와 같은 `EventEnvelope` 다. `aggregateType` 은 `PREORDER_CAMPAIGN`, `aggregateId` 는 상품 id 다.

```json
{
  "productId": "0199a3f2-8a10-7b21-9c32-4d5e6f708192",
  "scheduleVersion": 3,
  "opensAt": "2026-11-01T01:00:00Z",
  "closesAt": "2026-11-02T01:00:00Z",
  "visible": true,
  "expectedVisitors": 20000,
  "changedAt": "2026-10-22T03:04:05.123456Z",
  "change": "RESCHEDULED"
}
```

| 칸 | 뜻 |
| --- | --- |
| `scheduleVersion` | 회차의 일정 번호. 바뀔 때마다 오른다 |
| `opensAt` · `closesAt` | 접수를 여는 · 닫는 시각(ISO-8601 UTC). 일정 변경은 오픈 전에만 한다. 오픈 뒤 조기 마감 · 다시 열기는 `closesAt` 만, 오픈 전 마감은 `opensAt` 도 `closesAt` 바로 앞으로 바꾼다 |
| `visible` | 회원에게 공개한 회차인가. false 면 증설하지 않는다 |
| `expectedVisitors` | 관리자가 넣은 예상 방문자 수. 넣지 않았으면 `null` — 인프라의 기본 증설 규모를 쓴다 |
| `change` | `CREATED` · `RESCHEDULED` · `CLOSED` · `REOPENED` · `VISIBILITY` · `RESYNC`. 판정은 일정 번호로 하고 이 값은 기록용이다 |

- 일정 · 예상 방문자 수 변경은 오픈 10분 전까지 가능하다(`nova.campaign.min-lead-time`). 마지막 변경도 오픈 10분 전에 오므로, 인프라는 증설을 10분 안에 끝낼 수 있게 잡는다.
- 오픈 전 마감(판매 중지)도 `CLOSED` 로 온다. 증설 예약을 지우는 판정은 `closesAt` 이 지금 이전인지로 한다 — `opensAt` 은 보지 않는다.
