-- 상품의 보증(애플케어 등)을 옵션 문서(products.options)의 warranty 로 옮긴다 — 보증은 구매 때 옵션처럼 더해 고르는 것이라 옵션 정의와 같은 곳에 둔다.
-- 모양: "warranty": {"offered": true|false, "surcharge": 정수 원}. 제공하지 않으면 추가금은 0 이다.
--
-- 채우기만 하므로 구버전 catalog 도 그대로 돈다 — 새 catalog 보다 먼저 적용한다(칸 삭제는 다음 마이그레이션).
-- 이 마이그레이션부터 구버전 종료까지 상품 등록 · 수정을 받지 않는다. 구버전은 warranty 칸을 쓰고 옵션 문서를 통째로 다시 쓰면서 warranty 키를
-- 지운다 — 다음 마이그레이션이 키가 빠진 행만 칸에서 다시 채우지만, 구버전의 보증 변경 자체는 문서에 오지 않는다.
UPDATE shop.products
   SET options = JSON_SET(options, '$.warranty',
           JSON_OBJECT('offered', IF(warranty_offered = 1, CAST('true' AS JSON), CAST('false' AS JSON)),
                       'surcharge', IF(warranty_offered = 1, warranty_surcharge, 0)));
