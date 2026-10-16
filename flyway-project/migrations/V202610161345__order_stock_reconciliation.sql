-- 재고 장부 대조 · 관리자 보정(order 소유).
--
-- 왜: 재고(option_inventories 의 확보 · 판매)는 주문 · 드로우 회차 행과 같은 트랜잭션에서만 바뀌므로 주문 쪽에서 다시 셀 수 있다.
--     어긋나도(수동 SQL · 버그) 지금은 알 길이 없고, 관리자는 총량만 바꿀 수 있어 확보 · 판매는 DB 를 직접 고쳐야 한다.
--     하루 한 번 주문 기준 수량과 장부를 맞대 보고, 어긋난 옵션은 관리자가 "주문 기준으로 다시 맞추기" 로 고친다.
-- - order_items 에 ix_order_item_option (option_id, order_id, quantity): 옵션 하나의 주문 기준 수량(보정 · 미리보기)을 그 옵션의 주문상품만 읽어
--   센다. 전체 대조도 옵션 순서로 이 색인만 읽는다. 대가는 주문상품 INSERT 마다 색인 갱신 한 번이다.
-- - stock_adjustments: 보정 한 번의 기록 — 바꾸기 전 · 뒤의 확보 · 판매와 그때 총량, 사유, 요청한 관리자(인증 주체 이름 — 관리자는 회원 표 밖의
--   단일 계정이라 id 가 없다. DLQ 의 redrive_requested_by 와 같은 방식). 값이 그대로면 남기지 않는다.
-- - stock_reconcile_runs: 끝난 대조 한 번 — 어긋난 옵션 수와 그 목록(detail, 앞 1000개). 실패한 대조는 남기지 않는다(마지막 성공 시각이 경보 기준).
-- 업무 표로 가는 외래 키는 두지 않는다 — 기록은 그때 본 값이고, 넣을 때 업무 행을 잠그지 않게 한다.
--
-- 배포: 색인 추가는 INPLACE · LOCK=NONE 이라 그동안 order_items 쓰기를 막지 않는다(시작 · 끝의 짧은 메타데이터 잠금은 남는다 — 열린 트랜잭션이
--     있으면 기다린다). 새 표 · 색인만 더하므로 구버전과 같이 돌아도 되고, 서비스 배포 전에 적용한다.

ALTER TABLE shop.order_items
    ADD KEY ix_order_item_option (option_id, order_id, quantity),
    ALGORITHM = INPLACE, LOCK = NONE;

CREATE TABLE shop.stock_adjustments (
    id              binary(16)   NOT NULL,
    option_id       binary(16)   NOT NULL,
    before_reserved int          NOT NULL,
    before_sold     int          NOT NULL,
    after_reserved  int          NOT NULL,
    after_sold      int          NOT NULL,
    stock_total     int          NOT NULL,
    reason          varchar(500) NOT NULL,
    requested_by    varchar(64)  NOT NULL,
    created_at      datetime(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY ix_stock_adjustment_option (option_id, created_at),
    CONSTRAINT ck_stock_adjustment_nonnegative CHECK (before_reserved >= 0 AND before_sold >= 0 AND after_reserved >= 0
        AND after_sold >= 0 AND stock_total >= 0),
    CONSTRAINT ck_stock_adjustment_capacity CHECK (after_reserved + after_sold <= stock_total)
) ENGINE = InnoDB;

CREATE TABLE shop.stock_reconcile_runs (
    id            binary(16)  NOT NULL,
    started_at    datetime(6) NOT NULL,
    finished_at   datetime(6) NOT NULL,
    drift_options int         NOT NULL,
    -- {"options": [{optionId, productId, registered, total, expectedReserved, actualReserved, expectedSold, actualSold}], "truncated": bool}
    detail        json        NOT NULL,
    PRIMARY KEY (id),
    KEY ix_stock_reconcile_run_finished (finished_at),
    CONSTRAINT ck_stock_reconcile_run_period CHECK (started_at <= finished_at),
    CONSTRAINT ck_stock_reconcile_run_drift CHECK (drift_options >= 0)
) ENGINE = InnoDB;
