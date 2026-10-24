-- 운영값 변경 이력을 최신부터 읽는다.
-- KEYS  1 변경 이력(스트림)
-- ARGV  1 끝 id(포함, '(' 를 붙이면 제외, '+' 는 끝까지)  2 시작 id('-' 는 처음부터)  3 최대 개수
-- 반환  {{id, {필드, 값, ...}}, ...}

return redis.call('XREVRANGE', KEYS[1], ARGV[1], ARGV[2], 'COUNT', ARGV[3])
