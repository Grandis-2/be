-- 엔티티 id 를 BIGINT AUTO_INCREMENT 에서 UUID v7 BINARY(16) 으로 바꾼다.
--
-- 적용 순서: 모든 서비스 종료 → 이 마이그레이션 → 새 버전 기동. 구버전과 새 버전이 함께 돌 수 있는 중간 단계가 없다 —
-- 구버전은 AUTO_INCREMENT 에 기대 id 없이 INSERT 하고 숫자 id 를 읽는다. 적용 중에 구버전이 쓴 행은 오류 없이 엉뚱한 바이트 id 가 되고,
-- 그 상태에서 외래 키 재생성이 실패하면 DDL 이 원자적이지 않아 외래 키가 빠진 스키마가 남는다.
-- 큐(SQS · DLQ)와 대기열 Redis 에 남은 옛 숫자 id 메시지 · 키도 같은 때 비운다(새 버전이 읽지 못한다).
--
-- id 는 서비스(Hibernate @UuidGenerator, VERSION_7)가 INSERT 전에 만든다. 앞 48비트가 밀리초 시각이라 새 행이 클러스터 인덱스 끝에 붙는다.
-- 서비스 경계를 넘는 참조(주문의 상품 · 옵션, 예약의 회원 등)와 아웃박스 · 데드레터의 aggregate_id 도 같은 형으로 바꾼다.
-- 숫자 id 를 UUID 로 옮길 규칙이 없고 운영 데이터가 없으므로 바꾸는 표를 비운다. 형이 다른 칸 사이에는 외래 키를 둘 수 없어
-- 외래 키를 모두 내렸다가 같은 정의로 다시 건다.
--
-- BIGINT 로 남기는 것: 아웃박스 행 id(릴레이가 id 순으로 발행한다) · preorder_sync_attempts.id(시도 기록 순서) ·
-- 이벤트 순번 · 버전 · 대기 순번 · 배송 묶음 구간. 식별자가 아니라 순서 값이다.

-- 1. 외래 키를 내린다
ALTER TABLE shop.cart_items DROP FOREIGN KEY fk_cart_customer;
ALTER TABLE shop.cart_items DROP FOREIGN KEY fk_cart_option;
ALTER TABLE shop.categories DROP FOREIGN KEY fk_category_parent;
ALTER TABLE shop.dead_letter_events DROP FOREIGN KEY fk_dead_letter_preorder;
ALTER TABLE shop.dead_letter_events DROP FOREIGN KEY fk_dead_letter_redriven_from;
ALTER TABLE shop.option_inventories DROP FOREIGN KEY fk_inventory_option;
ALTER TABLE shop.order_events DROP FOREIGN KEY fk_order_event_order;
ALTER TABLE shop.order_items DROP FOREIGN KEY fk_order_item_option_ref;
ALTER TABLE shop.order_items DROP FOREIGN KEY fk_order_item_order;
ALTER TABLE shop.orders DROP FOREIGN KEY fk_order_customer;
ALTER TABLE shop.orders DROP FOREIGN KEY fk_order_preorder;
ALTER TABLE shop.preorder_campaigns DROP FOREIGN KEY fk_campaign_product;
ALTER TABLE shop.preorder_events DROP FOREIGN KEY fk_preorder_event_preorder;
ALTER TABLE shop.preorder_sync_attempts DROP FOREIGN KEY fk_sync_attempt_job;
ALTER TABLE shop.preorder_sync_jobs DROP FOREIGN KEY fk_sync_job_preorder;
ALTER TABLE shop.preorders DROP FOREIGN KEY fk_preorder_batch;
ALTER TABLE shop.preorders DROP FOREIGN KEY fk_preorder_customer;
ALTER TABLE shop.preorders DROP FOREIGN KEY fk_preorder_option;
ALTER TABLE shop.product_options DROP FOREIGN KEY fk_option_product;
ALTER TABLE shop.product_reviews DROP FOREIGN KEY fk_review_product;
ALTER TABLE shop.products DROP FOREIGN KEY fk_product_category;
ALTER TABLE shop.refresh_tokens DROP FOREIGN KEY fk_refresh_customer;
ALTER TABLE shop.shipment_batches DROP FOREIGN KEY fk_batch_product;

-- 2. 바꾸는 표와, 바뀐 aggregate_id 를 담은 아웃박스 표를 비운다
TRUNCATE TABLE shop.cart_items;
TRUNCATE TABLE shop.catalog_outbox_events;
TRUNCATE TABLE shop.categories;
TRUNCATE TABLE shop.customers;
TRUNCATE TABLE shop.dead_letter_events;
TRUNCATE TABLE shop.option_inventories;
TRUNCATE TABLE shop.order_events;
TRUNCATE TABLE shop.order_items;
TRUNCATE TABLE shop.order_outbox_events;
TRUNCATE TABLE shop.orders;
TRUNCATE TABLE shop.payment_outbox_events;
TRUNCATE TABLE shop.payment_transactions;
TRUNCATE TABLE shop.payments;
TRUNCATE TABLE shop.preorder_campaigns;
TRUNCATE TABLE shop.preorder_events;
TRUNCATE TABLE shop.preorder_outbox_events;
TRUNCATE TABLE shop.preorder_sync_attempts;
TRUNCATE TABLE shop.preorder_sync_jobs;
TRUNCATE TABLE shop.preorders;
TRUNCATE TABLE shop.product_options;
TRUNCATE TABLE shop.product_reviews;
TRUNCATE TABLE shop.products;
TRUNCATE TABLE shop.refresh_tokens;
TRUNCATE TABLE shop.shipment_batches;

-- 3. 형을 바꾼다. MODIFY 가 AUTO_INCREMENT 를 걷어 낸다. 기본 키 · 인덱스 · NULL 여부는 그대로다
ALTER TABLE shop.cart_items
    MODIFY id BINARY(16) NOT NULL,
    MODIFY customer_id BINARY(16) NOT NULL,
    MODIFY option_id BINARY(16) NOT NULL;
ALTER TABLE shop.catalog_outbox_events
    MODIFY aggregate_id BINARY(16) NOT NULL;
ALTER TABLE shop.categories
    MODIFY id BINARY(16) NOT NULL,
    MODIFY parent_id BINARY(16) NULL;
ALTER TABLE shop.customers
    MODIFY id BINARY(16) NOT NULL;
ALTER TABLE shop.dead_letter_events
    MODIFY id BINARY(16) NOT NULL,
    MODIFY aggregate_id BINARY(16) NULL,
    MODIFY preorder_id BINARY(16) NULL,
    MODIFY customer_id BINARY(16) NULL,
    MODIFY redriven_from_id BINARY(16) NULL;
ALTER TABLE shop.option_inventories
    MODIFY option_id BINARY(16) NOT NULL;
ALTER TABLE shop.order_events
    MODIFY order_id BINARY(16) NOT NULL;
ALTER TABLE shop.order_items
    MODIFY id BINARY(16) NOT NULL,
    MODIFY order_id BINARY(16) NOT NULL,
    MODIFY product_id BINARY(16) NOT NULL,
    MODIFY option_id BINARY(16) NOT NULL;
ALTER TABLE shop.order_outbox_events
    MODIFY aggregate_id BINARY(16) NOT NULL;
ALTER TABLE shop.orders
    MODIFY id BINARY(16) NOT NULL,
    MODIFY customer_id BINARY(16) NOT NULL,
    MODIFY preorder_id BINARY(16) NULL;
ALTER TABLE shop.payment_outbox_events
    MODIFY aggregate_id BINARY(16) NOT NULL;
ALTER TABLE shop.payment_transactions
    MODIFY id BINARY(16) NOT NULL,
    MODIFY target_id BINARY(16) NOT NULL;
ALTER TABLE shop.payments
    MODIFY id BINARY(16) NOT NULL,
    MODIFY target_id BINARY(16) NOT NULL;
ALTER TABLE shop.preorder_campaigns
    MODIFY product_id BINARY(16) NOT NULL;
ALTER TABLE shop.preorder_events
    MODIFY preorder_id BINARY(16) NOT NULL;
ALTER TABLE shop.preorder_outbox_events
    MODIFY aggregate_id BINARY(16) NOT NULL;
ALTER TABLE shop.preorder_sync_attempts
    MODIFY sync_job_id BINARY(16) NOT NULL;
ALTER TABLE shop.preorder_sync_jobs
    MODIFY id BINARY(16) NOT NULL,
    MODIFY preorder_id BINARY(16) NOT NULL;
ALTER TABLE shop.preorders
    MODIFY id BINARY(16) NOT NULL,
    MODIFY customer_id BINARY(16) NOT NULL,
    MODIFY product_id BINARY(16) NOT NULL,
    MODIFY option_id BINARY(16) NOT NULL,
    MODIFY shipment_batch_id BINARY(16) NOT NULL;
ALTER TABLE shop.product_options
    MODIFY id BINARY(16) NOT NULL,
    MODIFY product_id BINARY(16) NOT NULL;
ALTER TABLE shop.product_reviews
    MODIFY id BINARY(16) NOT NULL,
    MODIFY product_id BINARY(16) NOT NULL,
    MODIFY customer_id BINARY(16) NOT NULL,
    MODIFY order_item_id BINARY(16) NOT NULL;
ALTER TABLE shop.products
    MODIFY id BINARY(16) NOT NULL,
    MODIFY category_id BINARY(16) NOT NULL;
ALTER TABLE shop.refresh_tokens
    MODIFY id BINARY(16) NOT NULL,
    MODIFY customer_id BINARY(16) NOT NULL;
ALTER TABLE shop.shipment_batches
    MODIFY id BINARY(16) NOT NULL,
    MODIFY product_id BINARY(16) NOT NULL;

-- 4. 외래 키를 같은 정의로 다시 건다
ALTER TABLE shop.cart_items ADD CONSTRAINT fk_cart_customer FOREIGN KEY (customer_id) REFERENCES shop.customers (id);
ALTER TABLE shop.cart_items ADD CONSTRAINT fk_cart_option FOREIGN KEY (option_id) REFERENCES shop.product_options (id);
ALTER TABLE shop.categories ADD CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES shop.categories (id);
ALTER TABLE shop.dead_letter_events ADD CONSTRAINT fk_dead_letter_preorder FOREIGN KEY (preorder_id) REFERENCES shop.preorders (id);
ALTER TABLE shop.dead_letter_events ADD CONSTRAINT fk_dead_letter_redriven_from FOREIGN KEY (redriven_from_id) REFERENCES shop.dead_letter_events (id);
ALTER TABLE shop.option_inventories ADD CONSTRAINT fk_inventory_option FOREIGN KEY (option_id) REFERENCES shop.product_options (id);
ALTER TABLE shop.order_events ADD CONSTRAINT fk_order_event_order FOREIGN KEY (order_id) REFERENCES shop.orders (id);
ALTER TABLE shop.order_items ADD CONSTRAINT fk_order_item_option_ref FOREIGN KEY (product_id, option_id) REFERENCES shop.product_options (product_id, id);
ALTER TABLE shop.order_items ADD CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES shop.orders (id);
ALTER TABLE shop.orders ADD CONSTRAINT fk_order_customer FOREIGN KEY (customer_id) REFERENCES shop.customers (id);
ALTER TABLE shop.orders ADD CONSTRAINT fk_order_preorder FOREIGN KEY (preorder_id, customer_id) REFERENCES shop.preorders (id, customer_id);
ALTER TABLE shop.preorder_campaigns ADD CONSTRAINT fk_campaign_product FOREIGN KEY (product_id) REFERENCES shop.products (id);
ALTER TABLE shop.preorder_events ADD CONSTRAINT fk_preorder_event_preorder FOREIGN KEY (preorder_id) REFERENCES shop.preorders (id);
ALTER TABLE shop.preorder_sync_attempts ADD CONSTRAINT fk_sync_attempt_job FOREIGN KEY (sync_job_id) REFERENCES shop.preorder_sync_jobs (id);
ALTER TABLE shop.preorder_sync_jobs ADD CONSTRAINT fk_sync_job_preorder FOREIGN KEY (preorder_id) REFERENCES shop.preorders (id);
ALTER TABLE shop.preorders ADD CONSTRAINT fk_preorder_batch FOREIGN KEY (product_id, shipment_batch_id) REFERENCES shop.shipment_batches (product_id, id);
ALTER TABLE shop.preorders ADD CONSTRAINT fk_preorder_customer FOREIGN KEY (customer_id) REFERENCES shop.customers (id);
ALTER TABLE shop.preorders ADD CONSTRAINT fk_preorder_option FOREIGN KEY (product_id, option_id) REFERENCES shop.product_options (product_id, id);
ALTER TABLE shop.product_options ADD CONSTRAINT fk_option_product FOREIGN KEY (product_id) REFERENCES shop.products (id);
ALTER TABLE shop.product_reviews ADD CONSTRAINT fk_review_product FOREIGN KEY (product_id) REFERENCES shop.products (id);
ALTER TABLE shop.products ADD CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES shop.categories (id);
ALTER TABLE shop.refresh_tokens ADD CONSTRAINT fk_refresh_customer FOREIGN KEY (customer_id) REFERENCES shop.customers (id);
ALTER TABLE shop.shipment_batches ADD CONSTRAINT fk_batch_product FOREIGN KEY (product_id) REFERENCES shop.products (id);
