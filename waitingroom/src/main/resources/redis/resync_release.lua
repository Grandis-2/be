-- 재발행 요청 선점을 푼다. 내가 세운 표식(같은 토큰)일 때만 — 그사이 다른 노드가 세운 표식은 건드리지 않는다.
-- KEYS  1 resync 표식
-- ARGV  1 선점 토큰
-- 반환  1 풀었다 · 0 내 것이 아니다

if redis.call('GET', KEYS[1]) == ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 1
end
return 0
