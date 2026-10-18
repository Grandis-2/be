-- refresh_tokens.rotated_to: 이 토큰을 회전해 만든 다음 토큰의 id. 회전 직후 짧은 유예(30초) 안에 같은 옛 원문이 다시 오면, 그 다음 토큰이
-- 아직 한 번도 쓰이지 않았을 때만 재사용 탐지 대신 다시 회전한다 — 새로고침을 연타하면 브라우저가 앞 응답을 버려 새 쿠키를 받지 못하고 옛 쿠키로
-- 다시 오기 때문이다. 시각(rotated_at · created_at)은 초로 잘려 저장돼 같은 초 안의 연타에서 "다음 토큰" 을 가를 수 없어 id 로 가리킨다.
-- 외래키는 두지 않는다 — 정리 작업이 만료 행을 순서 없이 지운다. 기존 행은 NULL(유예 없이 지금처럼 재사용 탐지). 새 칸만 더하므로 새 버전보다 먼저
-- 적용해도 된다.
ALTER TABLE shop.refresh_tokens
    ADD COLUMN rotated_to binary(16) NULL AFTER rotated_at;
