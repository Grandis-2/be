-- preorders 에서 order 때문에만 남아 있던 키 하나와 옛 상태 이름을 걷어낸다.
--
-- 왜: uq_preorder_id_customer 는 orders 의 복합 외래 키(fk_order_preorder) 대상이었는데 그 키가 빠졌다(V202610111301).
--     id 가 PK 라 유일성도 겹쳐, 쓸 때마다 고칠 인덱스만 늘린다.
--     PAYABLE 은 REGISTERED 로 이름을 바꾸면서(V202610050158) order 시험 픽스처 때문에 CHECK 에 남겨 둔 것인데,
--     이제 order 시험은 예약 행을 넣지 않는다. preorders 에 쓰는 preorder 는 REGISTERED 만 쓴다.
-- 배포: 구버전 · 새 버전 모두 이 키 · 이름에 기대지 않으므로 서비스 배포 전에 적용한다.
--     PAYABLE 행이 남아 있으면 CHECK 추가가 실패해 멈춘다 — 그 행이 생길 경로가 없으므로 고치지 않고 드러낸다.
--     CHECK 는 같은 이름으로 다시 만들므로 지우기 · 만들기가 문장 둘이다. 두 번째에서 멈추면 그 행을 확인한 뒤 두 번째만 다시 돌린다.
--     preorders 에 열린 트랜잭션이 있으면 메타데이터 잠금을 기다린다. 쓰기가 적은 때에 돌린다.

ALTER TABLE shop.preorders
    DROP INDEX uq_preorder_id_customer,
    DROP CHECK ck_preorder_status,
    DROP CHECK ck_preorder_payable_from;

ALTER TABLE shop.preorders
    ADD CONSTRAINT ck_preorder_status
        CHECK (status IN ('PENDING_SYNC', 'REGISTERED', 'RESERVED', 'CANCELING', 'CANCELED')),
    ADD CONSTRAINT ck_preorder_payable_from
        CHECK (status <> 'REGISTERED' OR payable_from IS NOT NULL);
