-- 쓰지 않는 칸 정리. 둘 다 새 표로 대체됐고 읽거나 쓰는 코드가 없다 — catalog 엔티티는 매핑하지 않고, preorder · order 의 운영 코드와
-- 시험 픽스처(products INSERT 는 category_id · sale_mode · title · status · created_at · updated_at 만)도 이 칸을 쓰지 않는다(2026-10-04 확인).
-- categories.option_filter_definitions: 카테고리별 필터 정의(JSON) — 옵션 축 · 값 표(product_option_axes · product_option_values)로 대체.
-- products.image_url: 대표 이미지 URL — product_images 의 GALLERY 대표로 대체(목록 · 상세의 imageUrl 은 그 표에서 계산한다).
-- 두 칸을 참조하는 CHECK · 인덱스는 없다.
ALTER TABLE shop.categories DROP COLUMN option_filter_definitions;
ALTER TABLE shop.products DROP COLUMN image_url;
