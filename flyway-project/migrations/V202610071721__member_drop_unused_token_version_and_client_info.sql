-- 쓰지 않는 회원 칸 셋을 지운다.
--
-- customers.token_version: 액세스 토큰의 버전 클레임과 대조해 회원의 토큰을 한꺼번에 무효화하려던 칸. 엔티티에 매핑도 없고 읽는 코드가 없다 —
-- 회원 전체 폐기는 Redis 폐기 표식이 맡는다.
-- refresh_tokens.client_ip · user_agent: 발급 · 회전 때 저장만 하고 읽지 않았다(이상 로그인 확인 · 기기 목록 기능 없음).
-- 쓰지 않는 접속 IP 를 개인정보로 계속 쌓지 않는다. 필요해지면 그 기능과 함께 다시 둔다.
-- 세 칸을 참조하는 CHECK · 인덱스는 없다.
--
-- 적용 순서: 새 member 배포 → 구버전 member 종료 → 이 마이그레이션. 구버전은 client_ip · user_agent 를 매핑해 로그인 · 회전 때 INSERT 하므로
-- 먼저 지우면 로그인이 깨지고, 새 버전은 세 칸을 모르지만 남아 있어도 돈다 — client_ip · user_agent 는 NULL 허용, token_version 은 DEFAULT 0 이다.
-- (호환되지 않는 정리는 구버전 종료 뒤 — flyway-project/README 배포 순서)
ALTER TABLE shop.customers
    DROP COLUMN token_version;

ALTER TABLE shop.refresh_tokens
    DROP COLUMN client_ip,
    DROP COLUMN user_agent;
