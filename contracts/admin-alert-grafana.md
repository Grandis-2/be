# 관리자 알림 Grafana 메시지 계약 (Grafana → SNS → SQS → member)

서버 상태를 보는 관리자 알림(외부 등록 실패 · 대기열 · 5xx 비율 · 배송 대기 등)은 Grafana 경보 규칙이 지표로 판정하고, 그 경보를 member 가 받아 관리자 알림으로 저장한다. 이 문서는 그 메시지의 모양이다. 모양은 member 가 정하고 nova-infra 의 연락처 템플릿(`notify.yaml`) · 규칙(`rules.yaml`)이 맞춘다. 문서와 어긋나는 쪽이 고친다. 받는 사람 · 심각도 · 묶음 · 재알림은 `admin-alert-hub.md` 다.

## 전달

| 단계 | 이름 | 설정 |
| --- | --- | --- |
| Grafana 연락처 | 관리자 알림용 SNS 연락처 | `message` 템플릿이 아래 JSON 을 만든다. `disableResolveMessage: false`(해소도 보낸다) |
| SNS 주제 | `nova-prod-admin-alert` | |
| SQS 구독 | `admin-alert-grafana`(DLQ `admin-alert-grafana-dlq`) | 원문 전달(raw message delivery)을 켠다 — 본문이 곧 아래 JSON 이다 |
| 받는 쪽 | member | 받기 · 지우기 · DLQ 보내기 권한 |

- **메시지 속성이 없다.** SNS 원문 전달이라 `eventType` · `eventId` 가 실리지 않는다. member 는 본문만 읽는다.
- **최소 한 번 · 순서 보장 없음.** member 는 사건(아래 "사건")과 상태로 중복 · 늦은 메시지를 거른다.
- **정책.** 관리자 알림 라우트는 `admin_alert_type =~ ".+"` 로 고른다. `group_by: ['...']`(경보 하나에 메시지 하나), `group_wait: 10s`, `group_interval: 1m`, `repeat_interval: 1m`. Grafana 는 값만 바뀌면 다시 보내지 않으므로, 숫자 갱신과 5분 승격은 재전송으로만 member 에 온다. `repeat_interval` 이 `group_interval` 과 같아 실제 재전송은 약 2분마다다 — 숫자 갱신과 승격은 그만큼 늦게 반영된다.
- **질의 실패 · 지표 끊김.** 규칙의 `execErrState` 는 `KeepLast` 다 — 질의가 실패해도 가짜 해소가 오지 않는다. `noDataState` 는 게이지로 판정하는 6종(`REGISTER_FAILED` · `EVENT_DLQ_WAITING` · `QUEUE_BRAKING` · `QUEUE_SERVER_UNHEALTHY` · `EXTERNAL_SYSTEM_DOWN` · `SHIPPING_BACKLOG`)이 `KeepLast` 라, 지표가 끊겨도 앞 상태를 둔다. 요청량으로 판정하는 `SERVICE_*` 2종만 `OK` 다 — 요청이 없으면 정상으로 보고 해소가 온다. 그래서 member 는 해소를 그대로 믿는다.

## 메시지

```json
{
  "version": "1",
  "alerts": [
    {
      "status": "firing",
      "fingerprint": "8f3c2b1a9d0e4f57",
      "startsAt": "2026-10-26T05:02:11Z",
      "endsAt": "0001-01-01T00:00:00Z",
      "labels": {
        "alertname": "관리자 · 외부 등록 실패",
        "admin_alert_type": "REGISTER_FAILED",
        "admin_alert_target_kind": "NONE",
        "severity": "critical"
      },
      "annotations": {
        "metric_deadLetters": "860",
        "summary": "…"
      }
    }
  ]
}
```

| 칸 | 뜻 |
| --- | --- |
| `version` | `"1"` |
| `alerts` | 늘 1개다(`group_by: ['...']`, 템플릿은 프로비저닝 파일과 같은 규칙으로 `$` 를 쓰지 않아 `{{ with index .Alerts 0 }}` 로 만든다). member 는 여러 개도 받는다. 비면 계약 위반이다 |
| `status` | `firing` · `resolved` |
| `fingerprint` | Grafana 의 경보 지문. member 는 추적에만 쓴다 |
| `startsAt` | 사건이 시작된 시각. 초 단위 UTC(`{{ .StartsAt.UTC.Format "2006-01-02T15:04:05Z07:00" }}`). **사건의 이름이다** — 예외는 아래 "사건" |
| `endsAt` | `resolved` 면 해소 시각. `firing` 이면 Go 의 빈 시각(`0001-01-01T00:00:00Z`) — member 는 없음으로 읽는다 |
| `labels` | 규칙 라벨 전부. member 는 아래 셋만 읽는다(`alertname` · `severity` · `job` 등은 무시) |
| `annotations` | 규칙 주석 전부. member 는 `metric_` 으로 시작하는 것만 읽는다(`summary` 등은 무시) |

값의 `"` `\` 와 줄바꿈 · 탭은 기존 `chatbot.clean` 처럼 바꿔 JSON 이 깨지지 않게 한다.

### 라벨

| 라벨 | 필수 | 뜻 |
| --- | --- | --- |
| `admin_alert_type` | 예 | 알림 종류(`admin-alert-hub.md` 의 type). 40자까지 |
| `admin_alert_target_kind` | 아니오 | `NONE` · `PRODUCT` · `DRAW` · `SERVICE`. 없으면 `NONE` 으로 읽는다. 대상이 없는 규칙도 `NONE` 을 적어 보낸다 |
| `admin_alert_target_id` | 대상이 있으면 | 100자까지. `SERVICE` 는 job(`nova/order`)에서 `nova/` 를 뗀 서비스 이름이다 — 규칙 라벨 템플릿 `{{ reReplaceAll "^nova/" "" .Labels.job }}`. 결과 라벨에 job 도 남지만 member 는 무시한다 |

받는 사람 · 심각도는 라벨로 받지 않는다. `admin-alert-events.md` 의 "보내는 쪽은 사실만"과 같게 member 가 type 으로 정한다. `severity` 라벨은 Slack 라우팅에만 쓴다.

### 주석(`metric_*`)

`metric_<이름>` 이 metrics 의 `<이름>` 칸이 된다. 값은 규칙 주석에서 `{{ with .Values.B }}{{ .Value }}{{ end }}` 로 넣는다.

- 숫자로 풀리면 숫자로 담는다(`"860"` → `860`, `"0.25"` → `0.25`).
- 빈 문자열 · `NaN` · `+Inf` · `-Inf` · `Inf` · `<nil>`(Go 템플릿의 nil)은 `null` 로 담는다(`waitingroom_brake_factor` · `order_status_count` 는 재료가 없으면 NaN).
- 숫자가 JSON 칸에 담기지 않으면(double 범위 밖, 예: `1e400`) 계약 위반이다.
- 음수는 숫자 그대로 둔다(`snapshotAgeSeconds` 는 재료가 없으면 음수이고 그 자체로 뜻이 있다).
- 숫자가 아닌 글자는 문자열로 담는다.

★는 재알림을 견주는 주 숫자다(`admin-alert-hub.md` "재알림"). 이름을 바꾸면 재알림이 멈춘다. 값을 낼 재료(지표)가 없으면 그 칸은 `null` 이다 — 라벨 없는 규칙의 보조 질의가 지표가 없을 때 NaN 을 내기 때문이다. 창의 수(`errors` · `requests` · `mockCalls`)는 `increase` 라 60초 샘플에서 외삽한 값이고, 실제보다 크게 보일 수 있다 — 샘플이 두 개뿐인 2분 창(`SERVICE_ERROR_SPIKE` · `EXTERNAL_SYSTEM_DOWN`)이 특히 그렇다. 비율을 먼저 본다.

| type | `metric_*` | 뜻 · 단위 · 창 |
| --- | --- | --- |
| `REGISTER_FAILED` | ★`deadLetters` | 포기한 REGISTER 작업 수(max) |
| `EVENT_DLQ_WAITING` | ★`waiting`, `oldestAgeSeconds` | DLQ 대기 수, 가장 오래된 대기 나이(초) |
| `QUEUE_BRAKING` | `brakeFactor`, `preorderBadRatio`, `preorderP95Seconds` | 배율 min(0~1), waitingroom → preorder 호출 중 5xx · 오류 비율(0~1, 2분 — 2초 이상 느린 응답은 세지 않는다), 그 호출 p95(초, 2분 — 느린 응답은 이 값으로 본다) |
| `QUEUE_SERVER_UNHEALTHY` | `snapshotAgeSeconds`, `leaders`, `gateways` | 현황 나이 max(초, 음수 = 재료 없음), 리더 합(0 = 없음), 게이트웨이 수 max. waitingroom 지표가 5분 넘게 없으면(서비스가 통째로 내려감) 리더 없음으로 보고 울린다 — 그때 세 칸은 모두 `null` 이다 |
| `EXTERNAL_SYSTEM_DOWN` | `openWorkers`, `mockFailureRatio`, `mockCalls` | 차단기가 열림 · 반쯤 열림인 worker 수, TRANSIENT · UNKNOWN 비율(0~1, 2분), 호출 수(2분) |
| `SERVICE_ERROR_SPIKE` | `errorRatio`, `errors`, `requests` | 5xx 비율(0~1), 5xx 수, 요청 수(모두 2분) |
| `SERVICE_INCIDENT` | `errorRatio`, `errors`, `requests` | `errorRatio` 는 울린 쪽의 값이다 — 서비스 전체는 1분 간격 다섯 점의 2분 비율 중 최솟값(5분 동안 1분마다 잰 2분 비율이 모두 기준을 넘음 — 요청이 하한 아래인 분이 있으면 판정하지 않음), 핵심 경로(아래)는 2분 비율. `errors` · `requests` 는 5분 창(`increase[5m]`) |
| `SHIPPING_BACKLOG` | ★`total`, `preparingItems`, `readyToShip` | 두 상태 합, 상품 준비 수, 출고 대기 수 |

`SERVICE_INCIDENT` 의 핵심 경로는 결제 · 예약 접수다. POST 이고 `http_route` 가 다음 셋 중 하나인 요청이다. 실제 `http_route` 값은 Grafana 규칙을 켜기 전에 Mimir 에서 확인하고, 다르면 인프라 규칙과 이 문서를 함께 고친다.

| 기능 | `http_route` |
| --- | --- |
| 사전예약 접수 | `/api/v1/preorders` |
| 주문 결제 확인 | `/api/v1/orders/{orderId}/payment-attempts/{tossOrderId}/confirm` |
| 드로우 응모 결제 확인 | `/api/v1/draws/{drawId}/entries/me/payment-attempts/{…}/confirm` |

## 사건

- **규칙 하나 · 대상 하나에 경보 인스턴스는 하나다.** 질의를 `sum` 이나 `max by (대상)` 으로 묶어 결과 라벨에 `instance` 같은 값이 남지 않게 한다. 남으면 인스턴스마다 `startsAt` 이 달라 member 가 서로 다른 사건으로 보고 앞 사건을 닫아 버린다. 8종 모두 이 조건을 지킨다(결과 라벨은 `SERVICE_*` 에만 job 이 남는다).
- **사건 = (type, 대상, `startsAt`).** Grafana 는 한 사건 동안 `startsAt` 을 바꾸지 않는다. 예외는 Grafana 재기동(lgtm apply 등)이다 — 그때 회복 중(Recovering)이던 경보는 새 `startsAt` 으로 온다. member 는 아래처럼 앞 사건을 닫으므로 둘이 함께 열리지는 않지만, 새 알림으로 다시 열려 읽음이 풀린다. 같은 사건의 firing 은 숫자 갱신이고, resolved 가 오면 해소다. 해소 뒤에 늦게 온 firing 은 다시 열지 않는다. firing 보다 resolved 가 먼저 오면 해소된 알림으로 바로 남긴다.
- **새 사건이 오면** 같은 type · 대상의 열린 앞 사건을 닫는다(해소 메시지를 잃어도 열린 알림이 둘이 되지 않게). 같은 type · 대상에 더 늦게 시작한 사건이 이미 있으면 앞 사건의 늦은 메시지는 버린다.

## 라벨을 바꿀 때 주의할 것

규칙 라벨이나 식의 결과 라벨을 바꾸면 fingerprint 가 바뀌어 새 사건이 되고, 열린 알림은 해소 메시지 없이 끊긴다. type · 대상이 그대로면 member 가 새 사건을 받을 때 앞 사건을 닫지만, type 이름 · `admin_alert_target_kind` · `admin_alert_target_id` 까지 바뀌면 앞 알림이 열린 채 남는다. 운영 절차는 `admin-alert-hub.md` "열린 채 남는 알림"에 모았다.

## 실패 처리(member)

다음은 계약 위반으로 보고 아무것도 저장하지 않은 채 메시지를 `admin-alert-grafana-dlq` 에 바로 넣는다(`deadLetterReason` 속성).

| 이유 | 언제 |
| --- | --- |
| `UNREADABLE` | JSON 이 아니거나 객체가 아니다 |
| `INVALID_MESSAGE` | `version` 이 `"1"` 이 아니거나 `alerts` 가 비었다 |
| `INVALID_ALERT` | `status` 가 firing · resolved 가 아니다, `admin_alert_type` 이 없거나 40자를 넘는다, 대상 종류가 정한 값이 아니다, 대상이 있는데 id 가 없거나 100자를 넘는다, `startsAt` 이 없거나 풀 수 없다, `endsAt` 을 풀 수 없다, 시각이 담을 수 있는 범위(1000 ~ 9999년) 밖이다, 주 숫자(★)가 소수 넷째 자리로 반올림해 ±10^16 이상이다, 숫자가 double 범위 밖이다 |

모르는 type 은 저장하되 경고 로그를 남긴다(개발자 · 확인으로 둔다). 그래서 규칙을 member 보다 먼저 더해도 된다.

## 배포 순서

1. 인프라: SNS 주제 · `admin-alert-grafana` 큐 · 구독 · member 권한
2. member(이 큐의 소비기)
3. 인프라: Grafana 연락처 · 정책 · 규칙. member 가 받을 수 있게 된 뒤에 켠다. 큐가 먼저 있으면 member 보다 먼저 켜도 메시지는 큐에서 기다린다.

member 작업 정의의 `stop_timeout` 이 필요하다 — `admin-alert-hub.md` "종료".
