-- 재고 장부 대조 잠금 표(order 소유).
--
-- 왜: 대조는 인스턴스 하나만 돌아야 한다. 이름 잠금(GET_LOCK)은 연결에 묶여 대조 트랜잭션과 다른 연결로 잡아야 했고, 그 연결만 끊기면 잠금이
--     먼저 풀려 다른 인스턴스가 들어와 두 대조가 모두 stock_reconcile_runs 에 기록을 남길 수 있었다.
-- - stock_reconcile_lock: 행 하나(id = 1). 대조 트랜잭션이 이 행을 FOR UPDATE NOWAIT 로 잠근다 — 잠금이 대조 트랜잭션에 묶여 커밋 · 롤백(연결이
--   끊긴 경우 포함)과 함께 풀리므로, 잠금만 풀리고 앞 대조가 계속 기록하는 경우가 없다. 행은 여기서 넣는다 — 지우면 대조가 오류로 끝난다.
--
-- 배포: 새 표만 더한다. 서비스 배포 전에 적용한다 — 새 서비스는 이 행이 없으면 대조를 오류로 끝낸다. 구버전(이름 잠금)과 신버전은 서로를
--     막지 못하므로, 한쪽이 대조하는 도중에 다른 쪽이 대조를 시작하면 한 번 더 기록될 수 있다(앞 대조가 이미 끝났으면 2시간 간격 판정이 막는다).

CREATE TABLE shop.stock_reconcile_lock (
    id tinyint NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_stock_reconcile_lock_single CHECK (id = 1)
) ENGINE = InnoDB;

INSERT INTO shop.stock_reconcile_lock (id) VALUES (1);
