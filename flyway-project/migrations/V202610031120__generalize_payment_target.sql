-- 결제 대상 일반화 — payments · payment_transactions 의 order_id 를 (target_type, target_id) 로 바꾸고 orders 외래 키를 없앤다.
--
-- 왜: 결제가 독립 payment 서비스가 됐고, 주문(ORDER)에 더해 럭키 드로우 응모(DRAW_ENTRY, 응모비 선결제)도 결제한다.
--     결제는 대상의 상태를 모른다. 대상이 있는지 · 결제할 수 있는지는 부르는 서비스(order · draw)가 판정하고,
--     결과는 대상 서비스가 받아 자기 상태를 바꾼다. 그래서 대상 테이블을 가리키는 외래 키를 두지 않는다
--     (다른 서비스의 테이블이고, 대상 종류마다 테이블이 다르다).
-- 규칙:
--     "주문당 결제 1건"(uq_payment_order) → "대상당 결제 1건"(uq_payment_target).
--     대상별 거래 조회는 uq_payment_tx_active 의 앞부분(target_type, target_id)으로 찾고, 대상별 몇 행을 id 로 정렬한다.
--     (target_type, target_id) 인덱스를 따로 두어도 MySQL 8.4 는 비용이 같아 UNIQUE 쪽을 고른다(4행 · 6만 행 EXPLAIN 실측)
--     — 쓰이지 않는 인덱스라 두지 않는다. 버려진 PENDING 이 한 대상에 수백 개씩 쌓이면 그때 다시 본다.
--     앱은 id 순으로 읽는다 — created_at 은 앱 시계라 서버마다 달라 순서를 정하는 데 쓰지 않는다.
--     target_type 은 ORDER · DRAW_ENTRY 만. 새 대상은 CHECK 를 넓히는 새 마이그레이션으로 더한다.
--     "대상당 진행 중 · 성공 거래 1건"(uq_payment_tx_active) — 결제사 호출 전에 막는다.
--         결제 결과 UNIQUE(uq_payment_target)만으로는 이중 청구를 막지 못한다. 청구는 결제사 호출 때 일어나고, 그 뒤
--         기록 단계에서 막히면 청구된 돈이 기록 · 환불 없이 남는다(복구가 같은 키로 재전송해도 또 막혀 끝없이 반복).
--         결제가 order 안에 있을 때는 주문 상태 전이(결제 대기 → 승인 중)가 대상 단위로 직렬화했는데, 독립 서비스가 되면서
--         그 보장이 사라져 거래 테이블이 직접 진다.
--         active_marker: 진행 중 · 성공인 거래만 유형 값(CAPTURE · REFUND), 나머지는 NULL(NULL 끼리는 중복 허용).
--             CAPTURE 는 PROCESSING · RETRY_SCHEDULED · SUCCEEDED. PENDING 은 결제창만 연 것이라 여럿 있어도 된다 —
--                 시작(PENDING → PROCESSING)에서 두 번째가 막힌다. FAILED 뒤에는 새 시도를 연다.
--             REFUND 는 PENDING 부터. 환불 행은 결제된 뒤에만 열리고 워커가 곧 보내므로, 여는 순간 두 번째가 막혀야 한다.
--             결과 불명(PROCESSING)이 남으면 그 대상의 새 결제가 막힌다 — 결과를 모르는 채 다시 청구하지 않는다(의도).
--         preorders.active_marker 와 같은 방식이다(MySQL 에는 부분 인덱스가 없다).
--
-- 기존 행은 옮기지 않는다. 이 변경 시점에 결제 행을 쓰는 서비스가 배포된 적이 없어(order 의 결제 코드는 미배포,
--     payment 는 빈 뼈대) 옮길 행이 없다. 결제 행이 있는 DB 라면 새 target_type 칸이 빈 문자열로 채워져 CHECK 에 걸리고
--     그 테이블의 ALTER 전체가 실패한다 — 조용히 틀리지 않는다. payment_transactions 를 먼저 바꾼다 — 결제 기록(payments)은
--     거래 성공으로만 생기므로 결제 행이 있으면 거래 행도 있다. 그래서 어디든 행이 있으면 첫 문장에서 멈춰, 한 테이블만
--     바뀐 채 남지 않는다. 그때는 order_id → ('ORDER', order_id) 로 채우는 별도 절차가
--     필요하다. 테이블마다 칸 · 키 · 제약 변경을 한 ALTER 로 묶어, 실패하면 그 테이블은 바뀌지 않는다
--     (MySQL DDL 은 문장 사이에서 되돌리지 않는다). 개인 로컬 DB 는 새로 만든다(README).

ALTER TABLE shop.payment_transactions
    -- 외래 키를 먼저 없애야 그 키가 쓰던 인덱스를 지울 수 있다.
    DROP FOREIGN KEY fk_payment_tx_order,
    DROP INDEX ix_payment_tx_order,
    RENAME COLUMN order_id TO target_id,
    ADD COLUMN target_type varchar(20) NOT NULL AFTER id,
    ADD COLUMN active_marker varchar(10) GENERATED ALWAYS AS (CASE
            WHEN transaction_type = 'CAPTURE' AND status IN ('PROCESSING','RETRY_SCHEDULED','SUCCEEDED') THEN 'CAPTURE'
            WHEN transaction_type = 'REFUND' AND status IN ('PENDING','PROCESSING','RETRY_SCHEDULED','SUCCEEDED') THEN 'REFUND'
        END) STORED AFTER status,
    ADD UNIQUE KEY uq_payment_tx_active (target_type, target_id, active_marker),
    ADD CONSTRAINT ck_payment_tx_target_type CHECK (target_type IN ('ORDER','DRAW_ENTRY'));

ALTER TABLE shop.payments
    DROP FOREIGN KEY fk_payment_order,
    DROP INDEX uq_payment_order,
    RENAME COLUMN order_id TO target_id,
    ADD COLUMN target_type varchar(20) NOT NULL AFTER id,
    ADD UNIQUE KEY uq_payment_target (target_type, target_id),
    ADD CONSTRAINT ck_payment_target_type CHECK (target_type IN ('ORDER','DRAW_ENTRY'));
