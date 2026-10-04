-- 운영값 한 칸을 바꾸거나 지우고 그 전 값을 돌려준다. 한 번에 하므로 동시에 바꿔도 감사 로그의 전 값이 맞다.
-- KEYS  1 settings
-- ARGV  1 칸 이름  2 'set' 또는 'clear'  3 새 값(set 일 때)
-- 반환  전 값(없었으면 false)

local before = redis.call('HGET', KEYS[1], ARGV[1])
if ARGV[2] == 'set' then
    redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
else
    redis.call('HDEL', KEYS[1], ARGV[1])
end
return before
