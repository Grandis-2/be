-- 예약 상태 PAYABLE → REGISTERED 이름 변경.
--
-- 왜: PAYABLE 은 "결제해도 된다" 는 허가처럼 읽혀, 결제 기한이 지난 뒤에도 이름과 사실이 어긋났다.
--     이 상태가 뜻하는 것은 "외부 등록이 끝나 결제를 기다리는 중" 이다. 결제할 수 있는지는 결제 기한으로 판단한다.
-- 배포: 아직 운영 배포 전이라 한 번에 바꾼다(앱을 내리고 이 마이그레이션 뒤 새 앱). 이미 운영 중이면 두 이름을 함께 읽는 단계가 필요하다.
-- 이력(preorder_events)의 상태 이름도 함께 바꾼다 — 앱이 이력을 상태 enum 으로 읽는다.

ALTER TABLE shop.preorders
    DROP CHECK ck_preorder_status,
    DROP CHECK ck_preorder_payable_from;

UPDATE shop.preorders SET status = 'REGISTERED' WHERE status = 'PAYABLE';
UPDATE shop.preorder_events SET from_status = 'REGISTERED' WHERE from_status = 'PAYABLE';
UPDATE shop.preorder_events SET to_status = 'REGISTERED' WHERE to_status = 'PAYABLE';

ALTER TABLE shop.preorders
    ADD CONSTRAINT ck_preorder_status
        CHECK (status IN ('PENDING_SYNC', 'REGISTERED', 'RESERVED', 'CANCELING', 'CANCELED')),
    ADD CONSTRAINT ck_preorder_payable_from CHECK (status <> 'REGISTERED' OR payable_from IS NOT NULL);
