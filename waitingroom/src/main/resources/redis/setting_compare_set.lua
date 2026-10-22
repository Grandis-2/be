-- 운영값 한 칸이 읽은 때 그대로일 때만 바꾼다. 판정한 뒤 다른 관리자가 바꿨으면 쓰지 않아 다시 읽고 판정하게 한다.
-- KEYS  1 settings
-- ARGV  1 칸 이름  2 읽은 때 있었으면 'present', 없었으면 'absent'  3 읽은 값  4 새 값
-- 반환  {1 바꿈 / 0 그사이 바뀜, 전 값(없었으면 nil)}

local before = redis.call('HGET', KEYS[1], ARGV[1])
local same
if ARGV[2] == 'present' then
    same = before == ARGV[3]
else
    same = before == false
end
if not same then
    return {0, before}
end
redis.call('HSET', KEYS[1], ARGV[1], ARGV[4])
return {1, before}
