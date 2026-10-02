-- 상품 등록이 preorder · order 에 넣을 값(회차 · 차수 · 초기 재고)을 이벤트(catalog_outbox_events)로 보내게 바꾸면서,
-- catalog 가 다른 서비스를 직접 부르며 단계를 기록하던 칸을 지운다. 등록 기록은 멱등 키 → 상품 대응만 남는다.
--   단계 시각(campaign_set_at · batches_set_at · stock_set_at) · 완료(completed_at) · 막힘(blocked_reason · last_error) · 리스(lease_token · lease_expires_at)
--   requested_visible: 관리자가 고른 공개 여부는 등록 때 products.visible 에 바로 쓴다. 노출은 판매 방식별 준비(회차 · 재고 행)를 함께 본다.
-- CHECK 가 칸을 쓰고 있으면 칸을 지울 수 없다(MySQL 8.4.11 실측: 3959, ALTER 전체가 거절된다). 같은 ALTER 에서 제약을 먼저 지운다.
-- 기존 미완료 등록은 이관하지 않는다 — 이 칸들은 catalog 에픽에만 있었고(V202609302122) 배포된 데이터가 없다. 미완료 행이 있었다면 공개 여부는 0 으로 남고 이벤트도 없다.
ALTER TABLE shop.product_registrations
    DROP CHECK ck_registration_lease,
    DROP CHECK ck_registration_outcome,
    DROP COLUMN requested_visible,
    DROP COLUMN campaign_set_at,
    DROP COLUMN batches_set_at,
    DROP COLUMN stock_set_at,
    DROP COLUMN completed_at,
    DROP COLUMN blocked_reason,
    DROP COLUMN last_error,
    DROP COLUMN lease_token,
    DROP COLUMN lease_expires_at;
