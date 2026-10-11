-- 쓰지 않는 catalog 칸 둘을 지운다.
--
-- categories.code: 카테고리 응답에 복사만 되고 앱 · 프론트 어디도 읽지 않았다(조회 메서드는 시험에서만 불렸다). 유일 키도 같이 지운다.
-- product_options.display_attributes: 필터가 아닌 축의 표시값 JSON. 옵션 상세 응답에만 실렸고 같은 응답의 selections(모든 축 → 값)와
-- 겹친다. 로직 · SQL 에서 읽는 곳이 없다.
-- 두 칸을 참조하는 CHECK 는 없고, 인덱스는 uq_category_code 하나다.
--
-- 적용 순서: 새 catalog 배포 → 구버전 catalog 종료 → 이 마이그레이션. 구버전은 두 칸을 매핑해 읽으므로 먼저 지우면 깨지고,
-- 새 버전은 두 칸을 모르지만 남아 있어도 돈다 — 카테고리는 앱이 넣지 않고(시드 전용), display_attributes 는 NULL 허용이다.
-- (호환되지 않는 정리는 구버전 종료 뒤 — flyway-project/README 배포 순서)
ALTER TABLE shop.categories
    DROP INDEX uq_category_code,
    DROP COLUMN code;

ALTER TABLE shop.product_options
    DROP COLUMN display_attributes;
