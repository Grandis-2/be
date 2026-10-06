-- product_reviews 를 catalog 가 맡는다(리뷰 작성 · 조회). 그전까지 이 표를 쓰는 코드가 없어 행이 없다.
--
-- 다른 서비스 표로 가던 FK 를 뺀다 — customers(member) · order_items(order). catalog 는 그 표를 읽지 않는다.
-- 리뷰를 쓸 자격(그 회원의 주문상품인가 · 배송 완료 · 일반 주문)은 order 내부 API 로, 작성자 표시명은 member 내부 API 로
-- 작성 때 묻는다. 같은 서비스인 products 로 가는 FK 는 남긴다.
-- FK 를 지워도 그 FK 가 쓰던 인덱스는 남으므로 함께 지운다(order_item_id 는 uq_review_order_item 이 따로 맡는다).
--
-- author_name 은 작성 때 받은 표시명을 가린 값이다(첫 글자 + "**"). 회원이 이름을 바꿔도 이미 쓴 리뷰는 그대로다.
ALTER TABLE shop.product_reviews
    DROP FOREIGN KEY fk_review_customer,
    DROP FOREIGN KEY fk_review_order_item;

ALTER TABLE shop.product_reviews
    DROP INDEX fk_review_order_item,
    ADD COLUMN author_name varchar(10) NOT NULL AFTER option_title_snapshot;
