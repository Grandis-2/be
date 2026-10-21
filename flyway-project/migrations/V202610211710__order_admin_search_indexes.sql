-- 관리자 주문 목록의 조건 · 정렬 색인(order 소유).
--
-- 왜: orders 에는 created_at 으로 시작하는 색인이 없어 관리자 목록은 조건이 없으면 orders 전체를 읽고 정렬한다. 조건이 그 범위를 줄이려면
--     조건 칸으로 시작하는 색인이 있어야 한다.
-- - orders 에 ix_order_created (created_at): 최신순 목록을 색인 거꾸로 읽고(정렬 없음), 기간(from · to)을 범위로 읽는다.
-- - orders 에 ix_order_status_created (status, created_at): 상태 조건의 최신순을 그 상태의 행만 거꾸로 읽는다(정렬 없음). 이것이 없으면 위
--   색인이 생긴 뒤로 MySQL 이 "최신순 색인을 거꾸로 훑으며 상태를 거르면 곧 한 쪽이 찬다" 고 어림해, 옛날에 몰린 상태(오래된 취소 등)를 찾을 때
--   그보다 새 주문을 모두 훑을 수 있다. 결제 기한 배치가 쓰는 ix_order_due (status, payment_due_at) 는 그대로 둔다.
-- - orders 에 ix_order_ship_to_name (ship_to_name): 받는 분 이름 일치를 그 이름의 행만 읽어 찾는다.
-- - orders 에 가상 칼럼 ship_to_phone_last4 와 ix_order_ship_to_phone_last4: 받는 분 전화번호(형식 없는 문자열)의 숫자 뒤 4자리로 찾는다.
--   저장하지 않는 칼럼이라 표를 다시 쓰지 않는다. 앱은 읽기만 하고, 조건은 이 칼럼을 그대로 비교해야 색인을 탄다(식을 다시 쓰지 않는다).
-- - order_items 에 ix_order_item_product (product_id, order_id): 그 상품이 든 주문을 그 상품의 주문상품만 읽어 찾는다.
--   (product_id, option_id) 색인은 외래 키와 함께 지웠다(V202610111301). 옵션 조건은 ix_order_item_option 을 쓴다.
-- 대가는 주문 INSERT 마다 orders 보조 색인 4개(시각 · 상태와 시각 · 이름 · 전화 뒤 4자리 — 전화는 식 계산도), 주문상품 INSERT 마다 1개를 더
-- 갱신하는 것이다. 상태가 바뀔 때도 상태와 시각 색인을 갱신한다.
--
-- 배포: 모두 INPLACE · LOCK=NONE 이라 그동안 쓰기를 막지 않는다(시작 · 끝의 짧은 메타데이터 잠금은 남는다 — 열린 트랜잭션이 있으면
--     기다린다). 지원하지 않으면 복사로 넘어가지 않고 실패한다. 구버전과 같이 돌아도 되므로 서비스 배포 전에 적용한다.
-- 문장: orders 의 보통 색인 셋은 한 ALTER 로 묶어 함께 생기거나 함께 없다. 가상 칼럼과 그 색인은 한 문장에 묶으면 INPLACE 가 거절될 수 있어
--     나눈다. 그래서 문장은 넷이고 각각 따로 커밋된다.
-- 중간 실패: 앞 문장이 만든 색인 · 칼럼은 남고 Flyway 는 실패를 기록해 다음 적용을 막는다. 그대로 다시 적용하면 첫 문장이 이미 있는 색인을
--     만들려다 실패한다. 남은 것을 지워(DROP KEY ix_order_created, ix_order_status_created, ix_order_ship_to_name · DROP KEY
--     ix_order_ship_to_phone_last4 · DROP COLUMN ship_to_phone_last4 · order_items 의 DROP KEY ix_order_item_product 중 생긴 것) 적용 전 상태로
--     돌린 뒤 flyway.sh repair 로 실패 기록을 지우고 다시 적용한다(flyway-project/README.md 배포 순서와 실패 복구).

ALTER TABLE shop.orders
    ADD KEY ix_order_created (created_at),
    ADD KEY ix_order_status_created (status, created_at),
    ADD KEY ix_order_ship_to_name (ship_to_name),
    ALGORITHM = INPLACE, LOCK = NONE;

ALTER TABLE shop.orders
    ADD COLUMN ship_to_phone_last4 varchar(4)
        GENERATED ALWAYS AS (RIGHT(REGEXP_REPLACE(ship_to_phone, '[^0-9]', ''), 4)) VIRTUAL,
    ALGORITHM = INPLACE, LOCK = NONE;

ALTER TABLE shop.orders
    ADD KEY ix_order_ship_to_phone_last4 (ship_to_phone_last4),
    ALGORITHM = INPLACE, LOCK = NONE;

ALTER TABLE shop.order_items
    ADD KEY ix_order_item_product (product_id, order_id),
    ALGORITHM = INPLACE, LOCK = NONE;
