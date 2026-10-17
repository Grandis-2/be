# → catalog 이벤트 계약 (catalog-events 큐)

catalog 가 **받는** 이벤트의 기준이다. catalog 가 보내는 이벤트는 `contracts/catalog-events.md` 를 본다. 문서와 어긋나는 쪽이 고친다.

## 큐와 받는 쪽

- 큐 `catalog-events`, DLQ `catalog-events-dlq`(재수신 한도를 넘은 메시지를 큐의 재드라이브 정책이 옮긴다). 큐 · DLQ · 권한은 인프라가 만든다.
  - catalog 역할: `GetQueueUrl` · `ReceiveMessage` · `DeleteMessage` · `ChangeMessageVisibility`
  - 보내는 쪽 역할: `GetQueueUrl` · `SendMessage`
- 소비기는 **기본 꺼짐**이다(`nova.sqs.consumer.enabled`). 큐와 보내는 쪽이 준비된 뒤 설정으로 켠다. 켠 채 큐가 없으면 기동은 되고, 받기 실패를 WARN 으로 남기며 늦춰 다시 시도한다 — readiness 는 UP 이라 로그로만 드러난다.
- 봉투는 다른 서비스와 같은 `EventEnvelope`(`eventId` · `eventType` · `aggregateType` · `aggregateId` · `occurredAt` · `payload`)다.
- **보내는 쪽 의무:** `payload` 칸을 늘 싣는다(빈 객체라도). 보내는 쪽 JSON 매퍼가 빈 값을 빼는 설정(NON_EMPTY)이면 `{}` 가 빠져 DLQ 로 간다.

## 받는 이벤트

| 이벤트 | aggregate | 보내는 쪽 | catalog 가 하는 일 |
| --- | --- | --- | --- |
| (아직 없음) | | | |

종류를 더할 때는 이 표를 먼저 고치고, 처리기(`CatalogEventHandler` 빈)를 하나 더한다. 받는 쪽(catalog)이 먼저 배포된다 — 모르는 종류는 재시도 뒤 DLQ 로 간다.

## 처리 규칙 (`CatalogEventDispatcher`)

- 처리기는 `eventType` 이름으로 찾는다. 같은 종류를 처리기 둘이 맡으면 기동이 실패한다.
- 다음은 처리기를 부르지 않고 실패로 올린다 — 메시지를 지우지 않아 늦춰 다시 받다가 재수신 한도 뒤 DLQ 로 간다. 조용히 버리지 않는다.
  - JSON 이 아닌 본문 · 봉투 모양이 아님(JSON null · 배열 · 문자열 등) · 칸의 형이 틀림(`aggregateId` 가 UUID 가 아님 · `occurredAt` 형식) · `eventType` 또는 `payload` 가 빠짐
  - 처리기가 없는 종류
  - `aggregateType` 이 처리기와 다름 · `aggregateId` 가 없음
- `payload` 가 JSON `null` 이면 처리기에 NullNode 로 간다. payload 모양 검사는 처리기가 한다.
- **최소 한 번 · 순서 보장 없음.** 처리기는 같은 메시지를 두 번 받아도 결과가 같아야 하고, 늦게 온 옛 값이 새 값을 덮지 않게 한다(보내는 쪽 번호 등).
- 큐 쓰기 권한이 신뢰 경계다. 받은 이벤트에는 사용자 인증이 없다.

## 처리기 작성자가 알아 둘 것 — 종료와 오래 걸리는 처리

common:sqs 소비기의 동작을 그대로 물려받는다(리뷰 실측, wait-seconds 1 기준).

- 배포 · 종료 때 처리 중인 메시지는 `wait-seconds + 10초` 안에 끝나면 지워진다. 종료가 시작되면 새로 받지 않는다.
- 그보다 오래 걸리면 작업 스레드가 인터럽트된다. 그 뒤는 처리기가 인터럽트를 어떻게 다루느냐에 따라 갈린다(리뷰 실측, visibility 30초 · backoff 1초).
  - 인터럽트 표식을 되살려 던진다: 다시 보이기 늦추기도 실패해, 가시성 시간(`visibility`, 기본 5분) 뒤 재수신 횟수를 하나 쓰고 다시 온다.
  - 표식을 지우고 던진다: 늦추기가 성공해 backoff 만큼 뒤에 재수신 횟수를 하나 쓰고 다시 온다.
  - 인터럽트를 무시하고 정상으로 끝낸다: 지우기가 실패해 다시 오고, **처리가 두 번 일어난다** — 처리가 멱등이어야 하는 까닭이다.
  - 운영 값(wait 20초)이면 30초를 넘는 처리는 배포 때마다 위 셋 중 하나가 된다 — 처리는 짧게 둔다. JPA · JDBC 처리기가 어느 갈래인지는 재지 않았다.
