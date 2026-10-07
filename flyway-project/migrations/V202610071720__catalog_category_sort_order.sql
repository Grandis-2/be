-- 카테고리 표시 순서 칸.
-- 지금까지는 id(넣은 순서)가 메뉴 순서였는데, 상품이 category_id 로 가리키기 시작하면 행을 지웠다 다시 넣어 순서를 바꿀 수 없다.
-- 응답은 지금처럼 배열 순서가 곧 표시 순서다(sort_order 오름차순, 같으면 id 오름차순) — 칸 자체는 응답에 싣지 않는다.
-- 다른 모듈 시험 픽스처가 categories 를 이 칸 없이 INSERT 하므로 DEFAULT 0 을 둔다.
-- 추가만 하므로 구버전 catalog 도 그대로 돈다 — 새 catalog 보다 먼저 적용한다(flyway-project/README 배포 순서).
ALTER TABLE shop.categories
    ADD COLUMN sort_order int NOT NULL DEFAULT 0 AFTER name,
    ADD CONSTRAINT ck_category_sort_order CHECK (sort_order >= 0);
