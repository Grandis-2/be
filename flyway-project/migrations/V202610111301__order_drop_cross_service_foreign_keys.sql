-- order 표가 다른 서비스의 표를 가리키던 외래 키 4개와, 그 키 때문에만 있던 인덱스를 지운다.
--
-- 왜: 서비스 경계를 넘는 값은 앱이 확인한다 — 회원은 검증된 로그인 토큰에서, 예약 · 상품 · 옵션은 preorder · catalog 응답에서 온다.
--     외래 키는 그 확인을 한 번 더 할 뿐인데, 쓸 때마다 상대 서비스의 행에 공유 잠금을 걸어 그 서비스의 쓰기와 부딪히고
--     (재고 행 INSERT 와 catalog 의 옵션 수정), 상대 표의 제약을 order 시험이 따라가게 만든다(예약 행 픽스처).
--     payment(orders) · catalog 리뷰(customers · order_items)도 같은 이유로 외래 키를 두지 않는다.
-- 잃는 것: 사람이 SQL 로 고칠 때 없는 회원 · 예약 · 옵션을 가리키는 행을 DB 가 막지 못한다.
--
-- 구버전 · 새 버전 모두 이 키들에 기대지 않으므로 서비스 배포 전에 적용한다.
-- preorders 의 uq_preorder_id_customer 는 fk_order_preorder 의 대상이었지만 preorder 소유라 여기서 지우지 않는다.
-- 배포: 이 표들과 외래 키로 이어진 표(부모 customers · preorders · product_options, 자식 order_items · order_events 등)에 열린
--     트랜잭션이 있으면 메타데이터 잠금을 기다리고, 그동안 그 표의 조회도 뒤에 선다. 쓰기가 적은 때에 돌린다.
--     표마다 문장이 따로라 중간에 실패하면 남은 문장만 다시 돌린다.

ALTER TABLE shop.orders
    DROP FOREIGN KEY fk_order_customer,
    DROP FOREIGN KEY fk_order_preorder,
    -- 복합 외래 키 (preorder_id, customer_id) 를 받치려고 MySQL 이 만든 인덱스. 예약으로 찾는 조회는 uq_order_preorder 가 맡는다.
    -- fk_order_customer 를 받치던 ix_order_member_created 는 내 주문 목록이 쓰므로 남긴다.
    DROP INDEX fk_order_preorder;

ALTER TABLE shop.order_items
    DROP FOREIGN KEY fk_order_item_option_ref,
    -- 외래 키를 받치던 인덱스. (product_id, option_id) 로 찾는 조회가 없다.
    DROP INDEX ix_order_item_option_ref,
    -- 리뷰의 복합 외래 키 대상이었는데 그 키가 빠져(V202610061647) 가리키는 것이 없다. id 가 PK 라 유일성도 겹친다.
    DROP INDEX uq_order_item_product;

ALTER TABLE shop.option_inventories
    DROP FOREIGN KEY fk_inventory_option;
