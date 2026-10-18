-- preorder 표가 다른 서비스의 표(회원 · 상품 · 옵션)를 가리키던 외래 키 4개를 지운다. 인덱스는 모두 남긴다.
--
-- 왜: 서비스 경계를 넘는 값은 앱이 확인한다 — 회원은 검증된 로그인 토큰(대리 접수는 관리자 입력)에서, 상품 · 옵션은 catalog 응답에서 온다.
--     외래 키는 그 확인을 한 번 더 할 뿐인데, 접수 INSERT 마다 부모 행(회원 · 상품 · 옵션)에 공유 잠금을 걸어 인기 상품 오픈 때
--     catalog 의 상품 · 옵션 수정과 서로 기다리고, 다른 서비스 표에 묶여 서비스별로 배포 · 분리할 수 없게 한다.
--     order 도 같은 이유로 지웠다(V202610111301). preorder 표끼리의 외래 키(차수 · 이력 · 작업 · 차례 · DLQ · 오픈 알림)는 남긴다.
-- 잃는 것: 없는 회원 · 상품 · 옵션을 가리키는 예약 · 회차 · 차수 행을 DB 가 막지 못한다. 대리 접수의 없는 회원은 뒤의 알림 · 주문에서 드러난다.
--     회원을 지우는 코드는 아직 없다(상품은 소프트 삭제). 탈퇴를 만들 때 남는 예약의 처리를 같이 정한다.
--
-- 새 버전은 대리 접수에서 fk_preorder_customer 위반(404 MEMBER_NOT_FOUND)을 기대하지 않는다. 마이그레이션을 먼저 적용하고 서비스를 낸다 —
--     반대면 그사이 없는 회원 대리 접수가 외래 키 위반 500 이 된다. 구버전은 이 키가 없어도 동작한다(없는 회원 대리 접수가 그대로 들어간다).
-- 배포: 이 표들과 부모 표(customers · products · product_options)에 열린 트랜잭션이 있으면 메타데이터 잠금을 기다리고, 그동안 그 표의 조회도
--     뒤에 선다. 쓰기가 적은 때에 돌린다.
-- 중간 실패: 표마다 문장이 따로 커밋되므로 앞 문장에서 지운 외래 키는 지워진 채 남고, Flyway 는 실패를 기록해 다음 적용을 막는다.
--     지워진 외래 키를 V202610080907 의 정의대로 다시 걸어 적용 전 상태로 돌린 뒤 flyway.sh repair 로 실패 기록을 지우고 다시 적용한다
--     (flyway-project/README.md 배포 순서와 실패 복구). 다시 걸지 않고 다시 적용하면 첫 문장이 없는 외래 키를 지우려다 실패한다.

ALTER TABLE shop.preorders
    -- 받치던 uq_preorder_idempotency(customer_id, …) · ix_preorder_customer_created 는 재전송 확인 · 내 예약 목록이 쓴다
    DROP FOREIGN KEY fk_preorder_customer,
    -- 받치던 ix_preorder_option(product_id, option_id) 은 관리자 검색의 옵션 조건이 쓴다
    DROP FOREIGN KEY fk_preorder_option;

-- 받치던 것은 PK(product_id)다
ALTER TABLE shop.preorder_campaigns
    DROP FOREIGN KEY fk_campaign_product;

-- 받치던 uq_batch_number(product_id, …) 는 차수 번호의 유일성이고, uq_batch_product 는 fk_preorder_batch 의 대상이다
ALTER TABLE shop.shipment_batches
    DROP FOREIGN KEY fk_batch_product;
