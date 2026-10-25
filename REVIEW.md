# nova 리뷰 규칙

Qodo 리뷰 에이전트용 지시문이다. 0 · 1절은 모든 변경에, 2 · 3절은 운영 코드(`src/main`)에 적용한다(3절은 diff 경로에 맞는 절만). 시험 코드(`src/test`)에는 2 · 3절 대신 5절을 적용한다. 4 · 6절은 적힌 경로에, 7절은 PR 제목 · 설명에 적용한다.
"차단급"은 반드시 심각도 High 로 지적한다는 뜻이다. 근거(이 문서 · 코드 위치 · 마이그레이션)를 대지 못하면 지적하지 않는다.
스키마 정본은 `flyway-project/migrations` 다.

## 0. 리뷰 태도
- 한국어로 쓴다. 근거를 먼저 말하고, 확실하지 않으면 질문으로 남긴다.
- 지적하지 말 것: 포맷 · import 순서 · 줄바꿈 · 이름 취향 · 주석 유무(린터 · 취향의 영역), 테스트가 없다는 사실 자체.

## 1. 시스템 목표와 소유 경계
- 오픈 순간 신청이 몰려도 유실 0 · 외부 중복 등록 0 · 모든 예약이 최종 상태에 닿는다.
- 접수 완료 ≠ 외부 등록 확정 ≠ 결제 완료. 일시적인 외부 호출 실패는 예약의 최종 실패가 아니다.

| 모듈 | 쓰는 표 | 허용된 남의 표 |
| --- | --- | --- |
| member | customers · refresh_tokens · notifications | 없음 |
| catalog | categories · products · product_options · product_reviews · promotions · promotion_products · banners · catalog_outbox_events · images | 다른 서비스 표는 읽지 않는다 — 회차(시각 · 행 유무)는 preorder 의 `GET /internal/preorders/campaigns/**`(`PreorderCampaigns`), 재고는 order 의 `GET /internal/stocks` 로 묻는다 |
| preorder | preorders · preorder_events · preorder_campaigns · shipment_batches · preorder_outbox_events · preorder_dead_letter_events · preorder_sync_jobs · preorder_sync_attempts · preorder_open_alerts · preorder_open_alert_dispatches · preorder_payment_anomalies | reconciliation_runs 읽기 · 관리자 실행 요청(REQUESTED) INSERT, reconciliation_findings 읽기 · 조치 칸(action_*) UPDATE |
| order | orders · order_items · order_events · cart_items · option_inventories · draw_campaigns · draw_entries · order_outbox_events · stock_adjustments · stock_reconcile_runs · stock_reconcile_lock | 읽기: products · product_options(옵션 소속 · 판매 방식) |
| payment | payments · payment_transactions · payment_outbox_events · payment_dead_letter_events · payment_refund_admin_actions | 없음 |
| worker · waitingroom | 없음(worker 는 결과를 이벤트로, waitingroom 은 전용 Redis) | 없음 |
| batch:reconciliation | reconciliation_runs · reconciliation_findings | 읽기: 전부 |
| batch:imagecleanup | image_deletions | 읽기: 전부. 쓰기: images 의 UNUSED 행 삭제(3.5) |

## 2. 전역 규칙 — 적용 경로: `**/src/main/**/*.java`

### 2.1 정확성 · 결과 불명 · 시각 · 금액 · 로그
- 사용자 요청 경로(@RestController → @Service)에서 외부 HTTP 응답을 기다리면 지적한다. 외부 등록 · 취소는 worker 몫이다. 허용된 동기 호출:
  - 타임아웃 · 서킷 브레이커 · 동시 상한 필수: preorder → catalog 옵션(캐시 미스만 — 접수, 그리고 예약 목록 · 상세의 사진 `PreorderOptionImages`, 2026-10-23 결정. 사진은 못 받으면 비운다) · order 취소 사전 확인 · Mock 키로 조회 · 취소(정합성 조치 `ExternalMock` — 관리자가 결과를 기다리고, 일괄은 마감 시간 안에서만 시작한다)(`DependencyGuard`), order → catalog 옵션 일괄 조회(`CatalogReader`), preorder · order → member 회원 이름(관리자 예약 · 주문 목록 · 상세 `MemberNames` — 재시도 없음, 못 받으면 이름만 비운다, 2026-10-24 결정).
  - 타임아웃만: catalog → order · member(리뷰 작성, 2026-10-06 결정 · 리뷰 쓸 수 있는 주문상품 목록 `WritableReviewQuery` → `OrderClient#getDeliveredItems`, 2026-10-23 결정 · 상품 삭제의 드로우 증정품 확인 `ProductDeleteService` → `DrawGiftClient`, `CatalogDefaults`), catalog → order 재고(`StockReader` — 목록 · 상세 · 증정품 후보) · preorder 회차 일정(`PreorderCampaigns` — 목록 · 상세 · 관리자 · 수정 판정, 2026-10-19 결정. 수정 판정도 트랜잭션 전에 받는다), order → preorder 결제 가능 확인 · payment 결제 준비 · payment 관리자 결제 DLQ(`PaymentDeadLetters` — payment 를 ALB 에 열지 않고 order 가 중계, 읽기 5초 `payment-admin`, 2026-10-23 결정) · payment 관리자 환불 조치(`PaymentRefundAdmin` — 결제사 조회를 기다려 읽기 70초 `payment-refund-admin`, 2026-10-25 결정), member → 카카오 로그인(`KakaoOAuthClient`).
  - 결제 승인: order → payment(읽기 70초, `PaymentConfirmSlots`), payment → 토스(3.3).
  - 관리자 환불 조치: payment → 토스 조회(`RefundAdmin` — 동시 상한 `nova.payment.refund-admin.max-concurrent`, 넘으면 503, 2026-10-25 결정).
- 차단급: @Transactional 안의 HTTP 호출 · SQS 전송 · Thread.sleep · 외부 락 대기. 예외는 batch 정합성 검사 하나다 — be 를 한 스냅샷으로 읽는 검사 트랜잭션 안에서 Mock 목록(읽기)을 받는다(3.5).
- 차단급: 응답을 못 받은 외부 호출을 "실패" · "미등록"으로 단정. 결과 불명은 별도 상태로 두고 같은 키로 조회해 확정한다.
- 차단급: 토큰 · 카카오 식별자 · 결제 키를 로그 · 예외 메시지에 남김.
- 예외를 삼키거나 로그만 남기고 흘리는 catch, 내부 예외 · 스택트레이스 · SQL 을 사용자 응답에 싣는 코드를 지적한다.
- 알림의 SQS 전송 · member 처리 실패가 업무 트랜잭션을 되돌리면 지적한다. 알림은 업무를 막지 않는다(아웃박스 INSERT 는 업무와 같은 트랜잭션이 맞다).
- 저장은 UTC, 표시만 KST. `LocalDateTime.now()` 처럼 시스템 시간대에 기대면 지적. 금액은 원 단위(스케일 0)이고 float · double 은 지적.

### 2.2 동시성 안전
접수 · 재고 · 작업 선점 · 결제 반영 코드에서 아래는 차단급이다. 그 밖의 코드에서도 같은 패턴은 지적(Medium)한다.
- 조회 후 변경(check-then-act). UNIQUE 제약이나 조건부 UPDATE 로 바꾸게 한다.
- 조건부 UPDATE 의 영향 행 수를 보지 않음. "1행이 바뀌었을 때만 업무가 일어났다"가 기본 판정이다.
- 재시도 · 중복 수신에서 업무 효과가 두 번 일어남. 메시지는 최소 1회 전달이다.
- 예외는 order 관리자 재고 둘: `AdminStockService`(설정 · 초기화, catalog 등록 이벤트도 거침) · `StockCorrectionService`(`StockLedger#correct`). 같은 트랜잭션에서 PK 로 FOR UPDATE 한 뒤 불변식 · 읽은 값을 WHERE 에 다시 적은 조건부 UPDATE 의 영향 행으로 판정할 때만. 없는 행 확인 뒤 INSERT 는 유일 제약 + 중복 시 다시 하기가 있어야 한다. 확보 · 차감 · 반환에는 적용하지 않는다.

### 2.3 모듈 경계
쓰기와 읽기를 나눠 1절 표로 판정한다. `src/main` 만 본다(`src/test` 의 시험 데이터 준비 · 경쟁 재현은 제외).
- 차단급: 남의 표에 쓰기(허용 쓰기 제외).
- 차단급: 허용 읽기에 없는 남의 표 읽기.
- 소유가 바뀌는 PR 이면 README 소유 표와 1절을 함께 고치게 한다.

## 3. 모듈별 규칙

### 3.1 preorder — 적용 경로: `preorder/**`
**접수 트랜잭션**(`PreorderAcceptTransaction`) — 순서를 벗어나면 차단급: 회차 행 읽기(잠금 없음) → 같은 접수 키(customer_id, idempotency_key) 확인 → 상품 · 오픈 확인 → preorders(순번 · 차수 비움) · preorder_events → preorder_sync_jobs(REGISTER) → preorder_outbox_events(REGISTER_JOB_READY) → flush → 조건부 UPDATE 로 회차 잠금 · 순번 발급(공개 · 판매 중지 · 기간 재확인) → 순번 · 배송 차수 채우기 → 커밋 후 응답. 순번 발급 뒤에 다른 쓰기가 오면 잠금 구간이 길어져 지적. 안에서 HTTP · SQS 발행이 있으면 차단급.

**불변식**
- 순번은 preorder_campaigns.next_queue_position 을 조건부 UPDATE 로 잠그고 올린다(QueuePositionCounter). MAX+1 · AUTO_INCREMENT · 앱 카운터 · Redis INCR 대체는 차단급.
- 모델당 활성 예약 1건은 uq_preorder_active 로 막는다. 조회 후 INSERT 로만 막으면 지적.
- 같은 키 + 같은 내용 → 기존 예약 반환(새 순번 금지), 같은 키 + 다른 모델 · 옵션 → 거절. uq_preorder_idempotency 는 취소 행도 포함한다. 재신청은 새 키 · 새 순번이다.
- 배송 차수는 접수 순번 구간으로 계산한다(외부 등록 순서 · 확정 시각 금지). 오픈 뒤 shipment_batches · 기존 배정 변경은 지적.
- 사전예약이 재고를 차감 · 확보하면 지적한다(결정 3).
- 상태 변경은 현재 상태 조건 UPDATE + preorder_events INSERT 를 한 트랜잭션에서. DEAD_LETTER 를 예약 최종 실패로 바꾸면 차단급.

**외부 작업 결과**(EXTERNAL_JOB_SUCCEEDED · ATTEMPTED · FAILED)
- 순서: 예약 행 잠금 → 작업 읽기 → 차례 기록 → 작업 상태 조건부 UPDATE(취소 · 재처리와 같은 순서). 어기면 차단급.
- 끝난 작업(SUCCEEDED · CANCELED)을 늦은 결과가 되돌리면 지적. DEAD_LETTER 뒤의 성공은 받는다.
- 시도 기록은 eventId 로 한 번(uq_sync_attempt_event 위반으로 판정). 조회 후 INSERT · (작업 · 세대 · 차례) 거르기는 지적.
- attempt · run 없는 ATTEMPTED · FAILED 는 조용히 처리하지 않는다. 받은 이벤트 · 이전 run 은 기록만 남긴다. SUCCEEDED 는 run 으로 거르지 않는다.
- 재처리는 DEAD_LETTER → PENDING(run + 1)이 1행일 때만 작업 종류에 맞는 REGISTER_JOB_READY · CANCEL_JOB_READY 를 올린 run 으로 다시 적는다. 0행인데 발행하거나 run 을 싣지 않으면 지적.
- 작업 메시지의 request 는 저장한 request_payload 그대로다. 본문을 새로 만들거나 키를 바꾸면 차단급.
- CANCEL 의 FAILED 도 작업을 DEAD_LETTER 로 두고 예약은 CANCELING 에 남긴다. 받은 자리에서 다시 발행하거나 예약을 CANCELED 로 보내면 지적. 끝내는 길은 재처리 뒤 성공 · 늦게 온 성공 · 관리자 수동 완료(CANCEL · DEAD_LETTER · 예약 CANCELING 일 때만)뿐이다.
- 작업 DLQ(preorder-register-dlq · preorder-cancel-dlq)의 메시지는 FAILED 와 같게 작업을 DEAD_LETTER 로 두고 지운다(JobDeadLetterConsumer). 메시지 run 이 작업 run 과 같을 때만 바꾼다 — 작으면(지난 실행) 그대로 두고 지우고, 크면 계약 위반이라 지우지 않는다. 원본 큐로 다시 보내거나 예약 상태를 바꾸면 지적. 어느 작업인지 읽을 수 없거나 없는 작업의 메시지는 지우지 않는다(DLQ 경보).

**경계**
- 상품은 `GET /internal/products/{productId}/options` 로 묻고 캐시한다. 이름 · 가격은 접수 때 예약 행에 복사한다 — 표시용 이름 · 가격을 매번 카탈로그 조회로 만들면 지적한다.
- `/internal/**` · `/actuator/**` 는 공개 경로가 아니다. 사용자 기능을 두거나 `/internal/**` 이 토큰 주인 확인 없이 응답하면 지적.

### 3.2 order — 적용 경로: `order/**`
- 차단급: preorders · preorder_events 쓰기(결과는 PREORDER_PAYMENT_CONFIRMED 로 알림), payments · payment_transactions 접근(`PaymentClient` · `PaymentConfirmer`, 환불은 ORDER_REFUND_REQUESTED). 예약을 CANCELED 로 바꾸면 지적.
- 금액은 주문의 저장 총액이다. 사용자 금액은 대조만 한다(D15). 그것을 payment 에 넘기면 차단급.
- 결제 대기로 되돌리는 것은 payment 의 "앞으로도 시작될 수 없다" 확언(NotStartable)뿐이다. Pending · Unanswered 로 되돌리면 차단급. payment 호출은 트랜잭션 밖이다.
- 결과 반영(`PaymentResults`)은 결과 하나 = 트랜잭션 하나, 승인 중 전제로 한 번만. 주문 전이와 뒷일(판매 확정 · 장바구니 차감, 사전예약은 아웃박스)이 함께 간다. 승인 응답과 이벤트가 겹쳐 두 번 반영되면 차단급.
- 재고 확보는 `UPDATE option_inventories SET stock_reserved = stock_reserved + ? WHERE option_id = ? AND stock_total - stock_reserved - stock_sold >= ?` 한 번, 0행 = 품절.
- 반환은 stock_released_at 이 빌 때 한 번만. 중복 반환을 막는 조건이 없으면 차단급.

### 3.3 payment — 적용 경로: `payment/**`
내부 전용이다. 토큰 유효성만 보고 주인 · 결제 가능 판정은 호출자가 한다.
- 원장: payments · payment_transactions 쓰기는 `PaymentLedger` 만(`PaymentArchitectureTest`). 우회 경로는 차단급.
- 원장은 MANDATORY 다. 거래 하나 = 트랜잭션 하나. 여러 건을 묶거나 원장 예외를 트랜잭션 안에서 잡고 계속하면 차단급.
- 반영 0행은 `LeaseLostException` 이다. 반환값으로 바꾸거나 @Retryable 로 재시도하면 지적.
- 잠금 순서는 결제 행 → 거래 행(`lockPaymentByTarget`)이다. 바뀌면 차단급. 대상 · 유형(CAPTURE/REFUND)당 진행 중 · 성공 거래 1건은 uq_payment_tx_active 로 막는다.
- 토스: 선점을 커밋한 뒤 트랜잭션 밖에서 부르고 새 트랜잭션에서 반영한다. `TossPaymentClient` 로만 부른다(`TossPaymentsApi` 직접 사용은 차단급, `HttpClientConfig` 만 예외). 결과 → `Outcome` 변환은 `TossOutcomes` 한 곳.
- 연결 + 읽기 ≤ 리스 70초, https, 리다이렉트 안 따름. 시크릿 키 · Authorization · 결제 키를 로그에 남기거나 RestClient 예외 객체를 로그에 넘기면(URL 에 결제 키) 차단급.
- 결과 불명: 표에 없는 코드 · 타임아웃 · 연결 실패 · 읽을 수 없는 응답 · 예상 밖 상태(`UnknownReason`). FAILED 는 확정 거절뿐이다. 불명 · 처리 중을 FAILED 로 굳히면 차단급. 시간 경과 전이는 보낸 적 없는 CAPTURE 의 만료(`PendingCaptureExpiry`)뿐.
- 복구(`CaptureRecovery` · `RefundRecovery`): 응답 없음 → 같은 키 재전송, 해석 불가 · 일시 오류 · 거절 → 조회로 확인, 모르는 상태 → 에스컬레이션(상태 그대로). 자동 확정은 차단급. 후보는 SKIP LOCKED 로 한 건씩 claim 한다(`RecoveryWorker`).
- 멱등 키는 거래 행 생성 때 발급하고 재전송은 그 키로 한다. 교체는 진행 중 요청이 없을 때 같은 행에서 `rotateIdempotencyKey` 로만. 새 키 재청구 · CAPTURE 새 행 재시도는 차단급.
- 승인은 호출자의 저장 금액과 대조한다(사용자 입력 금지). 환불은 저장된 성공 결제의 키 · 금액으로 연다.
- 다시 온 환불 요청은 열지 않고 성공(`RefundRequests`). 확정 실패 뒤 자동 재오픈은 지적(결정 33). 다시 여는 길은 관리자 조치(`RefundAdmin`) 하나다 — 결제사 조회로 "한 푼도 취소되지 않음"을 확인한 뒤 새 거래로만 열고(같은 행 되살리기 · 같은 멱등 키 재전송은 지적), 조치는 `payment_refund_admin_actions` 에 남긴다.
- 확정 결과 이벤트는 반영과 같은 트랜잭션에서 payment_outbox_events 에 적는다(`CaptureSettlement` · `RefundSettlement`). 불명 · 처리 중은 알리지 않는다.

### 3.4 worker — 적용 경로: `worker/**`

상태
- DB 를 쓰지 않는다. JPA · JDBC · 저장소를 들이거나 표를 읽고 쓰면 지적한다.
- 사전예약 작업은 내용을 본문에, 진행을 속성(run · attempt · generation · lastOutcome · actor — JobProgress)에 싣는다. run 은 preorder 가 정한다 — 바꾸거나 지연 재전송 때 빠뜨리면 지적한다.
- worker 끼리 상태를 나누는 코드(리더 선출 · 분산 잠금 · 공유 카운터)는 지적한다. MockCircuit · ConcurrencyLimiter 도 worker 마다 따로다.
- worker 가 예약 · 작업 상태를 판단하면 지적한다.
- worker 에 알림 발송이 들어오면 지적한다(contracts/notification-events.md).

메시지
- SQS 중복 전달은 정상이다. 같은 메시지를 두 번 받아 외부에 다른 키로 두 번 보낼 수 있으면 지적한다.
- 결과 이벤트 · 지연 재전송을 보내기 전에 원본을 지우면 지적한다. 못 보내면 던져 원본을 둔다.
- 처리 못 한 메시지를 결과 이벤트 · DLQ 없이 지우면 지적한다. 읽을 수 없는 작업 봉투도 지우지 않는다(지표 + 남겨 SQS 가 DLQ 로).
- 빈 자리 없이 꺼내 worker 안에 줄 세우면 지적한다(꺼낸 순간 가시성 시간이 흐른다). CANCEL 전용 자리를 없애면 지적한다.
- 지금 부를 수 없으면(차단기 · 거절 이어짐 · 자리 없음 · 내려가는 중) 차례 · 받은 횟수를 쓰지 않고 돌려보낸다. 호출하지 않고 attempt 를 올리면 지적한다.
- 결과 이벤트 id 는 (종류 · 작업 · run · 세대 · 차례), 추첨 결과는 회차로 정한다(ResultPublisher). 보낼 때마다 새로 만들거나 run 을 빼면 지적한다 — 재처리한 실행의 결과가 지난 실행으로 가려진다.

외부 호출
- 판정은 HTTP 상태보다 errorCode 가 먼저다(MockJudge). errorCode 없는 404 를 "등록 없음" · "응모 0건"으로 읽으면 지적한다 — 경로 오류라 차례를 쓰지 않고 돌려보내고, 처음 본 때(속성 misconfiguredSince)부터 reject-guard.give-up-after 뒤에만 포기한다(작업 FAILED · 드로우 DLQ).
- 거절 이어짐(RejectGuard)은 작업 종류마다 센다. 한 종류의 오류로 다른 큐의 받기를 멈추면 지적한다.
- 차단기는 걸린 시간을 보지 않는다(느림은 ConcurrencyLimiter 가 다룬다). 차단기에 경과 시간을 넘기거나 느린 호출 비율로 열면 지적한다.
- 자리 조절(ConcurrencyLimiter)은 기준점까지 두 배, 넘으면 increase-step 으로 늘린다. 줄일 때 기준점을 낮추고(차단기가 열리면 직전의 절반), 창의 호출이 minimum-calls 미만이면 줄이지 않는다(느림은 자리를 다 쓴 창이면 적어도 줄인다 — 느리면 끝나는 호출이 준다). 기준점 없이 늘 +1 이거나 한두 건으로 줄이면 지적한다.
- 타임아웃(연결 · 읽기) 없는 HTTP 클라이언트는 지적한다.
- 확정 4xx 를 차단기 실패로 세면 지적한다. 일시 실패 · 결과 모름만 센다.

관측 · 연결
- readiness 는 readinessState 와 consumers(받기 · 삭제 스레드 생존)만 본다. 외부 mock · SQS 상태를 넣으면 지적한다 — 그쪽 장애 한 번에 모든 태스크가 바뀐다.
- 설정 오류로 돌려보낼 때는 처음 본 때부터 지난 시간을 worker.misconfigured 에 남긴다. 돌려보낸 메시지는 큐 나이 경보에 잡히지 않는다.
- SQS 는 연결 수를 정한 HTTP 클라이언트(SyncConfig.sqsHttpClient, 동시 요청 상한 + 큐마다 넘칠 수 있는 한 묶음 · 받기 · 삭제)로 보낸다. 연결 수가 동시 요청 상한을 따라가지 않게 바꾸면 지적한다.
- 차례 안 즉시 재시도(InRoundRetry)는 저장 안 된 게 확실한 일시 실패만 한다.

사전예약 작업(REGISTER · CANCEL, JobHandler)
- 재시도는 같은 Idempotency-Key · 같은 본문이다. 새 키 · 바뀐 본문은 지적한다.
- 응답 없음 · 도중 끊김 · 본문 없는 5xx 를 미등록으로 단정하지 않는다. UNKNOWN 으로 두고 다음 차례에 같은 키로 조회해 확정한다.
- EXTERNAL_JOB_ATTEMPTED · EXTERNAL_JOB_FAILED 에 차례 기록(Attempt, run 포함)이 없으면 지적한다. preorder 가 그것으로 중복 · 지난 실행을 가린다.
- 차례 사이 지연이 고정이면 지적한다. 늘어나는 지연 + 지터, 상한 15분(RetrySchedule).
- 재시도 소진은 EXTERNAL_JOB_FAILED 로 알린다. CANCEL 은 retry.cancel-generations(generations 이상)까지 버틴다. 알리지 않고 버리면 지적한다.

드로우(DRAW_ENTRY_PAID · DRAW_REQUESTED, DrawHandler — contracts/draw-entry-sync.md)
- 작업 표가 없어 재시도 · 보관은 SQS 가 한다. 일시 실패 · 기다림은 지우지 않고 늦춰 다시 받는다(최대 수신 횟수를 넘으면 draw-register-dlq). 다시 보내도 같은 거절(확정 4xx · 계약과 다른 메시지 · 추첨 결과)만 DeadLetterSender 로 바로 DLQ 에 넣고 지운다. 일시 실패를 바로 DLQ 로 보내거나 확정 거절을 헛돌리면 지적한다.
- 결과를 모르면 조회 없이 같은 요청을 다시 보낸다(Mock 이 응모 id · 회차 id 로 재생). 응모 id 대신 새 키를 쓰면 지적한다.
- 응모는 마감 시각을 보지 않는다. 마감으로 거절하면 지적한다. 응모 결과는 order 에 알리지 않는다.
- 추첨 전 Mock 이 받은 응모 수(entryCount, 없음은 0)를 paidEntries 와 대조한다. 다르면 추첨하지 않고 점점 길게 늦춰 기다린다. 다른데 추첨하거나, 다름을 거절 · 즉시 DLQ 로 처리하거나, 추첨 요청에서 expectedEntryCount 를 빼면 지적한다. 추첨 뒤에는 빠진 응모를 되돌릴 수 없다.
- 당첨 목록은 winnerCount 개 · 겹침 없음 · UUID 일 때만 order 로 보낸다(DrawResultReady). 아니면 DLQ(INVALID_DRAW_RESULT). 못 보내면 던져 원본을 남긴다.
- 모르는 eventType 은 지우지 않는다. 종류 · payload 칸을 바꿀 때 worker 보다 order 가 먼저 나가면 질문한다.

### 3.5 batch — 적용 경로: `batch/**`

- 한 번에 하나만 도는 것이 전제다. 정합성 검사는 ECS 서비스 1대(desired 1)로 상시 돌고, 사진 정리는 Scheduler 가 매일 띄우는 단발 태스크다. 겹친 실행 방지(락 · 단일 실행 표식)가 없으면 지적한다.
- 사진 정리는 기동하면 한 번 돌고 종료 코드로 끝난다(0 마침 · 잠금으로 건너뜀 · S3 실패로 멈춤, 2 막힘, 1 예외). 배치 실패 경보가 이 값을 본다. 앱 안에 주기 작업(@Scheduled · cron)을 다시 두거나, 예외 · 막힘을 0 으로 끝내면 지적한다.
- 운영은 당분간 두 서비스가 batch 계정 하나를 같이 쓰고, 그 권한은 모듈별 계정 시험(ReconciliationAccountTest · ImageCleanupAccountTest) GRANT 의 합으로 맞춘다. 쓰는 표가 늘면 시험의 GRANT 와 함께 운영 batch 계정도 바꿔야 한다고 PR 설명에 적게 요청한다.
- 정합성 검사는 읽기 · 비교 · 결과 기록(reconciliation_runs, reconciliation_findings 는 INSERT 만)만 한다. 업무 표나 external_mock 에 쓰면 지적한다. 조치는 정상 업무 경로(관리자 → API · 이벤트)다.
- 조회 실패 · 권한 오류가 난 실행을 "불일치 0"으로 집계하면 지적한다. 검사 미완료로 남긴다.
- 검사 트랜잭션 안의 Mock 목록 받기(2.1 의 예외)는 목록 전체 기한(external.mock.max-duration)과 트랜잭션 제한 시간(run-timeout ≥ 목록 기한, 기동 때 검사) 안에서만 한다. 기한 없이 받거나 두 값의 관계 검사를 빼면 지적한다.
- 정상 처리 중인 차이(접수 직후 · 등록 진행 중)를 불일치로 세면 지적한다. 기준 시각이 필요하다.
- 전체를 읽어 메모리에서 처리하면 지적한다. 커서 · 청크로 끊는다.
- 업무 표 쓰기 예외는 사진 정리(images) 하나다. 기준 시간이 지난 UNUSED 행만 id 로 지운다(object_key 로 지우면 catalog 붙이기와 잠금 순서가 달라 교착). 같은 문장에서 어떤 draw_campaigns.image_url_snapshot · order_items.image_url_snapshot(지난 주문의 사진)도 그 키로 끝나지 않을 때만 지운다. 같은 트랜잭션에 image_deletions 를 넣고, S3 객체는 커밋 뒤 지운 행이 1개일 때만 지운다. 조건을 빼거나 USED 행 · images · image_deletions 밖 표를 지우면 지적한다.
- 사진 정리 기준 시간(retention)은 설정으로 바꾸지만 1시간보다 짧으면 기동하지 않는다(ImageCleanupProperties.MIN_RETENTION) — 저장 중인 드로우 회차가 고른 사진이 커밋 전에 지워지지 않게 드로우 생성 시간 예산보다 길게 둔다. 하한을 빼거나 낮추면 지적한다.
- 결제 기한 만료 취소는 preorder PaymentExpiryJob · order CartOrderExpiry(일반 주문)가 한다. batch 에 같은 만료 취소 · 예약 · 주문 상태 변경을 만들면 지적한다.

### 3.6 catalog — 적용 경로: `catalog/**`

읽기 경계
- 허용된 두 표(1절)는 읽기 전용 저장소 한 곳(ProductListingQueryRepository)에서만 읽는다. 다른 클래스가 읽으면 지적한다.
- 배송 안내(shipment_batches)는 프론트가 GET /api/v1/preorders/products/{id}/shipment-batches 를 부른다.
- 리뷰 자격(내 주문상품 · 배송 완료 · 일반 주문)과 작성자 이름(실명 우선)은 order · member 내부 API(contracts/order-internal.md · member-internal.md)로 묻는다.

상품 · 옵션
- 등록 뒤 sale_mode 를 바꾸는 길은 마감된 사전예약 → 일반 전환 하나다(ProductEditService.convertToInStock, POST /api/v1/admin/products/{id}/conversion). 판매 방식을 바꾼 뒤 IN_STOCK_PRODUCT_REGISTERED 를 보낸다 — order 의 재고 검증이 기댄다. 다른 경로가 생기거나 마감 전 · 회차 취소 상품을 전환하면 지적한다.
- sku 는 모델 안에서 유일하다. 앱 조회로만 검사하면 UNIQUE 제약을 제안한다.
- 상품 · 옵션 물리 삭제는 지적한다. 상품 삭제는 소프트 삭제(deleted_at, ProductDeleteService)이고 비공개다(ck_product_deleted_hidden). 공개 여부를 안 보는 경로(관리자 목록 · 상세 · 수정, 증정품 · 홍보 후보, 내부 조회)가 deleted_at 을 거르지 않으면 지적한다. 수정은 ProductRepository.findForUpdate 가 거른다. 예외: 등록 재생 · 등록 상태 조회는 같은 멱등 키에 그 상품 id 를 돌려준다.
- 홍보 묶기 검증은 공유 잠금(findAllForShare)으로 읽는다. 잠그지 않으면 지적한다.
- 모집 오픈 뒤 일정 · 배송 기준을 바꾸면 지적한다. 가격 · 이름 변경이 과거 예약에 소급되면 지적한다.
- 카테고리 parent_id 를 쓰며 순환(자기 자신 포함) · 3단계를 검사하지 않으면 지적한다. DB 는 못 막는다.
- 회원 노출 판정이 visible 만 보고 판매 방식별 준비(ProductListingQueryRepository.READY)를 안 보면 지적한다.
- 옵션 축 · 값 · 사진은 products.options(ProductOptions) 한 칸이다. 색상 사진을 color 축 값 밖에 두면 지적한다. Product.replaceOptions 밖에서 쓰거나(썸네일이 안 바뀐다) 행 잠금(findForUpdate) 없이 읽고 다시 쓰면 지적한다.
- 같은 값 검사를 collationKey 로만 하고 CollationDuplicates 를 건너뛰면 지적한다. combination_key · filter_attributes · title 을 OptionCombination 밖에서 만들면 지적한다.

이벤트 · 내부 API
- 다른 서비스가 만들 값(회차 · 차수, 초기 재고)을 그 API 를 동기로 불러 넣거나 그 표에 쓰면 지적한다. 같은 트랜잭션에서 OutboxWriter 로 적는다(contracts/catalog-events.md).
- catalog_outbox_events 를 OutboxWriter 밖에서 넣거나 업무와 다른 트랜잭션에서 append 하면 지적한다(MANDATORY). payload 에 상품 id 를 다시 넣으면 지적한다(aggregateId 로만). 예외 PREORDER_CAMPAIGN_CANCELED 는 payload 의 productId 를 빼면 지적한다.
- /internal/** 은 USER · ADMIN 만 받고 비공개 · 미완료 상품도 200 이다. 먼저 숨기면 지적한다. 예외: 삭제한 상품은 옵션 조회 404 PRODUCT_NOT_FOUND, 옵션 묶음에서 빠진다.

사진
- 사진 주소를 바꾸는 저장(등록 · 사진 넣기/바꾸기/빼기 · 홍보 만들기/고치기 · 상품 삭제)이 ImageAttachments.sync 를 같은 트랜잭션에서 안 부르면 지적한다. 붙이기는 멱등 키 INSERT 뒤다.
- images 를 id 가 아닌 object_key 로 잠그거나 고치면 지적한다. 붙일 것 · 뗄 것을 두 문장으로 나눠 잠가도 지적한다.
- 업로드 바이트를 그대로 S3 에 올리면 지적한다. 디코딩 뒤 다시 인코딩한 것만 올린다. 형식은 앞 바이트로, 픽셀을 풀기 전에 헤더 가로 · 세로를 본다. 동시 처리 허가 밖에서 파일을 힙에 다 올리면 지적한다.

### 3.7 member — 적용 경로: `member/**`

- 회원 리프레시는 DB 가 정본이고 SHA-256 만 저장한다(RefreshTokens.hash). 원문 저장 · Redis 사본은 지적한다. 관리자 리프레시만 Redis 다(AdminRefreshTokenStore).
- 회전 때 이전 토큰을 무효화하지 않으면 지적한다. 재사용을 감지하면 같은 체인(family_id) 전체를 폐기한다.
- 서명 키 · 카카오 시크릿 · 관리자 비밀번호 해시가 코드나 기본 설정값에 있으면 지적한다.
- 액세스 토큰 값을 로그 · 응답 · URL 쿼리에 남기면 지적한다.
- 카카오 응답을 그대로 엔티티에 매핑하면 지적한다. 필요한 필드만 옮긴다.
- 인증 실패가 "없는 계정"과 "잘못된 자격"을 구분해 알리면 지적한다.
- 관리자 로그인 시도 제한(AdminLoginLimit)을 bcrypt 뒤로 미루거나 빼면 지적한다.
- `/internal/customers/names`(order · preorder 관리자 화면의 회원 이름)는 토큰 주인 확인 대신 관리자 토큰만 받는다(2026-10-24 결정). 관리자가 아닌 토큰에 열거나 메서드를 묶은 매처로 걸면 지적한다 — HEAD 가 GET 매처를 비켜 GET 핸들러로 간다.

알림(contracts/notification-events.md)
- 중복은 (event_id, customer_id) 유일 키로 막는다. 넣은 회원을 한 번 읽고 나머지를 여러 행 INSERT 한 번으로 넣는다(NotificationWriter). 회원마다 INSERT 하거나 INSERT IGNORE 를 쓰면 지적한다. 행 id 는 DB 연결을 얻은 뒤 만든다 — 먼저 만들면 NotificationTail 이 놓친다.
- 계약 위반은 바로 DLQ(NotificationDeadLetters, deadLetterReason), 일시 오류는 던져 늦춰 다시 받는다. 둘을 섞으면 지적한다.
- 모르는 kind · 칸을 거절하면 지적한다. 문구 · target 은 보내는 쪽이 완성한다 — member 가 예약 · 응모를 읽어 만들면 지적한다.
- 조회 · 읽음은 모두 회원 id 조건을 단다. 남의 알림과 없는 id 는 같은 404 다.
- 실시간 연결(NotificationStreams)은 DB 를 읽지 않고, NotificationTail 주기 하나가 묶어 센다. 연결마다 DB 를 읽거나 목록 잠금을 쥔 채 쓰기 · 닫기를 하면 지적한다.

### 3.8 waitingroom — 적용 경로: `waitingroom/**`

구조 · 판정
- 이벤트 루프에서 블로킹 호출(동기 SQS · block())을 하면 지적한다. boundedElastic 으로 뺀다(SqsScheduleResyncRequester). 예외: 종료 정리(ControlPlane.stop, 2초 시한).
- DB · JPA, 서블릿 전용 common:web · common:security 에 의존하면 지적한다.
- 요청 경로가 Redis 를 치는 곳은 QueueStore(줄 서기 · 순서 조회 · 입장 기록 삭제)뿐이다. 나머지는 스냅샷(SnapshotHolder)으로 판정한다. 요청마다 Redis · member 를 부르는 판정을 더하면 지적한다.
- 줄 선 사람을 추월시키지 않는다(AdmissionDecider). 재료가 없거나 낡았거나 Redis 가 안 되면 줄 없는 통과를 내지 않는다 — 줄 또는 503(AdmissionGate). "모름"을 통과로 바꾸면 차단급.
- 오픈 · 마감 · 스냅샷 나이는 RedisClock 으로 잰다. 노드 시계로 판정하면 지적한다.
- SecondWindowLimiter 는 모델 · 노드 예산을 함께 차감한다. 따로 차감하면 지적한다.

Redis
- 여러 키를 함께 바꾸는 줄 연산은 resources/redis 의 Lua 한 번으로 한다(LuaScripts). 명령을 나눠 확인 뒤 쓰면 지적한다.
- 키는 RedisKeys 에서만 만든다(해시 태그 {productKey}, UUID 소문자 표준 표기). 다른 곳에서 키를 만들면 지적한다.
- 리더 전용 쓰기(배분 커서 · 스냅샷 발행 · 마감 · 청소)는 임기 펜스(Leadership)를 Lua 에 넘긴다. 펜스 없이 쓰거나 리스 밖에서 리더로 믿으면 지적한다.
- 일정 반영(CampaignScheduleHandler)은 일정 번호가 더 클 때만 쓴다. 비교를 빼면 지적한다.
- 깨진 운영값 · 일정 하나가 배분 전체를 멈추면 지적한다(OperationalSettings · ProductSchedules).

토큰 · 인증
- 입장권 · 대기 토큰은 HMAC 서명(SignedToken)이고 키는 설정(WAITING_TOKEN_*)으로만 받는다. preorder AdmissionTicketVerifier 와 형식이 같아야 한다 — 한쪽만 바꾸면 지적한다.
- 입장권 수명을 늘리면 preorder 수명 설정도 그 이상이어야 한다. waitingroom 만 늘리면 지적한다.
- AcceptRoute 는 접수 POST 만, 대기 토큰 · Origin 을 떼고 넘긴다. 다른 경로를 전달하면 지적한다. 입장권 없음 400, 서명 · 모델 · 회원 불일치 403(AdmissionTicketCheck). 1회 소비 · 최종 판정은 preorder 다.
- 액세스 토큰은 RS256 고정이다(JwtDecoderFactory). 토큰 머리의 alg 를 믿으면 지적한다.
- JWKS 는 뒤에서 받아 둔 키로만 답한다(JwkSetCache, 갱신 10초에 한 번까지). 요청이 받기를 기다리거나 모르는 kid 마다 member 를 부르면 지적한다. 키를 못 받으면 401 이 아니라 503 이다.
- 관리자 API 는 ADMIN 만이고, 운영값 변경은 바꾼 사람 · 시각 · 전후 값을 남긴다.

### 3.9 common — 적용 경로: `common/**`

- 도메인 개념(예약 · 주문 · 결제 · 상품)이 들어오면 지적한다. 공통은 응답 · 예외 · 커서 · 메시지 봉투 · 인증 검증 · outbox · SQS · Redis · CORS · 설정 읽기 · 시험 도구까지다.
- 서비스 모듈(member · catalog · preorder · order · payment · waitingroom · worker · batch:reconciliation · batch:imagecleanup)을 참조하면 지적한다.
- 쓰는 서비스가 모듈마다 다르다(각 build.gradle 로 확인). 서블릿과 waitingroom 이 함께 쓰는 common:cors 에 한쪽 스택 전용 타입을 넣으면 지적한다.
- common:types 는 모두가 의존한다. spring-web · security · JPA 구현(hibernate 를 compileOnly 밖으로)이 들어오면 지적한다.
- 공통 예외 · 에러 코드 · EventEnvelope · 자동설정 기본값을 바꾸면 영향 범위를 PR 설명에 적게 요청한다.

## 4. 빌드 — 적용 경로: `**/build.gradle`, `settings.gradle`

- 차단급: 서비스 모듈끼리의 `project(...)` 의존(예: preorder → order). 공유는 common 이나 API · 이벤트로 한다.
- BOM(Spring Boot · Spring Cloud AWS · AWS SDK)이 관리하는 라이브러리에 버전을 적으면 지적한다. 관리 밖 버전은 ext 한 곳(루트 awsSdkVersion 등)에 두게 제안한다.
- 새 의존성은 용도를 주석이나 PR 설명으로 남기게 요청한다. implementation 으로 충분한 것을 api 로 노출하면 지적한다.
- H2 등 MySQL 이 아닌 시험 DB 를 들이면 지적한다.
- CI 의 모듈 · 서비스 목록은 루트 serviceGraph 가 정본이다. 목록을 워크플로에 손으로 적게 바꾸면 지적한다.
- settings.gradle: 모듈 추가 · 삭제는 서비스 경계 변경이라 근거를 PR 설명에 요청한다. 아무도 의존하지 않는 모듈이 생기면 지적한다.

## 5. 테스트 — 적용 경로: `**/src/test/**`

- 정상 경로만이면 실패 · 동시성 경로를 요청한다. 같은 키 동시 재전송, 같은 작업 중복 선점, 재시도 · 중복 수신의 이중 반영, 중단 뒤 재시작.
- 동시 출발은 common:testing 의 Concurrently 를 쓰게 제안한다.
- DB 동작(SKIP LOCKED · UNIQUE 충돌 · 잠금 · CHECK · 영향 행)을 다른 엔진으로 검증하면 지적한다. MySQL 8.4(CI 서비스 컨테이너 또는 Testcontainers MySQLContainer)로 한다. Redis · Lua 는 실제 Redis, SQS 는 Floci 픽스처(common:sqs testFixtures).
- 고정 sleep 으로 비동기를 기다리면 지적한다. 시한 있는 조건 대기로 바꾼다.
- 테스트끼리 데이터를 공유해 순서에 기대거나 단정 없이 실행만 하면 지적한다.
- @Disabled · assumeTrue 로 건너뛰는 테스트를 더하면 지적한다.

## 6. 설정 · CI — 적용 경로: `**/src/main/resources/**`, `.github/workflows/**`

- 커밋되는 설정은 application.yml.example 뿐이다. application.yml 이 커밋되면 지적한다.
- 비밀번호 · 토큰 · 키를 값으로 적으면 지적한다. 환경 변수 · CHANGE_ME 자리표시로 둔다.
- DB · HTTP · Redis 클라이언트를 쓰는 모듈에서 풀 크기 · 타임아웃이 기본값 그대로면 지적한다. 접수 처리량을 좌우한다.
- ddl-auto 는 validate 다. update · create 면 지적한다(스키마는 Flyway 로만). show-sql 을 켠 채 병합하면 지적한다.
- 워크플로가 시크릿을 로그로 내거나 PR 이벤트에서 시크릿을 쓰면 지적한다.
- 새로 추가하는 액션은 커밋 SHA 고정을, permissions 는 최소 권한 명시를 제안한다.
- 테스트 · 마이그레이션 검사를 건너뛰거나 skipped 검사를 빼면 근거를 묻는다.
- main 은 promote.yml 만 옮긴다(dev 푸시 → build · database 통과 → fast-forward, App 키는 release 환경). 다른 워크플로가 main 에 쓰거나, App 키를 저장소 시크릿으로 옮기거나, PR 대상에 main 을 더하면 지적한다.

## 7. PR 형식 — PR 제목 · 설명에 적용

어기면 지적한다(Medium).
- 제목은 `<type>(<이슈 키>): <요약>`(예: feat(NV-7): 사전예약 접수 API 추가). type 은 feat · fix · refactor · test · docs · chore. 요약은 한국어, 마침표로 끝내지 않는다.
- 이슈 키는 Jira 키(NV-숫자)다. 작업 브랜치 PR 은 그 작업 키, 에픽 → dev PR 과 dev 를 에픽에 반영하는 PR 은 에픽 키다. 제목 · 브랜치(`<타입>/<이슈 키>[-설명]`)에 키가 없으면 연결된 이슈가 없다고 지적한다.
- 설명이 비었거나 템플릿의 "작업 내용" · "확인한 것"이 비면 지적한다.
