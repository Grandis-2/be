-- payment_transactions — 만료 상태 EXPIRED 와 에스컬레이션 시각 escalated_at (NV-102 결과 불명 복구).
--
-- EXPIRED: 한 번도 결제사에 보내지 않은 CAPTURE PENDING 을 닫는 종결 상태. 결제창만 열고 승인 요청이 오지 않았거나, 주문이 승인 중이
--     됐는데 결제 서비스가 시작하기 전에 멈춘 결제창이다. FAILED 로 닫을 수 없다 — FAILED 는 결제사가 확정한 실패이고 결제 키가
--     있어야 한다(ck_payment_tx_started_key). EXPIRED 는 보낸 적이 없으므로 키 · 시도 없이 허용한다.
--     active_marker 는 EXPIRED 를 세지 않는다(식이 PROCESSING · RETRY_SCHEDULED · SUCCEEDED 만 고른다) — 생성 칼럼은 그대로 둔다.
-- escalated_at: 복구 작업이 스스로 끝낼 수 없다고 판단해 멈춘 시각(반복 불명 상한 · 자동 확정 금지 상태). 상태는 그대로 두고 —
--     시간이 지났다는 이유로 실패로 굳히지 않는다 — 복구 후보에서만 뺀다. 관리자 해소는 이 칸으로 찾는다.
--     보낸 적 있는 거래에만 있다(ck_payment_tx_escalated).
--     에스컬레이션은 리스를 쥔 PROCESSING 에서만 일어나고 그 리스는 곧 만료된다. 그래서 리스 만료 인덱스에 escalated_at 을 끼워
--     (ix_payment_tx_lease → ix_payment_tx_recoverable) 복구 후보 조회가 "escalated_at IS NULL" 범위만 읽게 한다 — 그러지 않으면
--     에스컬레이션된 행이 만료 순서 맨 앞에 쌓여 매 폴링이 다시 훑는다. 만료 후보(status = 'PENDING')도 같은 앞부분을 쓴다.
-- 기존 행: 모두 이 규칙을 이미 만족한다(새 상태 · 새 칸이 없으므로). 배포: CHECK 교체 · 추가는 기존 행을 검사하므로
--     ALGORITHM=COPY 다(표 복사 · 그동안 쓰기 막힘). 지금 크기면 짧다 — 큰 표가 된 뒤라면 쓰기가 적은 때에 돌린다.

ALTER TABLE shop.payment_transactions
    ADD COLUMN escalated_at datetime(6) NULL AFTER finished_at,
    DROP INDEX ix_payment_tx_lease,
    ADD KEY ix_payment_tx_recoverable (status, escalated_at, lease_expires_at),
    DROP CHECK ck_payment_tx_status,
    DROP CHECK ck_payment_tx_started_key,
    ADD CONSTRAINT ck_payment_tx_status
        CHECK (status IN ('PENDING','PROCESSING','RETRY_SCHEDULED','SUCCEEDED','FAILED','EXPIRED')),
    ADD CONSTRAINT ck_payment_tx_started_key
        CHECK (status IN ('PENDING','EXPIRED') OR provider_payment_key IS NOT NULL),
    ADD CONSTRAINT ck_payment_tx_escalated
        CHECK (escalated_at IS NULL OR status NOT IN ('PENDING','EXPIRED'));
