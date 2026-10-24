-- 운영값 한 칸을 바꾸거나 지우고 그 전 값을 돌려준다. 한 번에 하므로 동시에 바꿔도 감사 로그의 전 값이 맞다.
-- 변경 이력도 같은 스크립트에서 남겨 값만 바뀌고 이력이 빠지는 일이 없다. 이력은 건수 · 기간 한도 밖을 바로 지운다.
-- KEYS  1 settings  2 변경 이력(스트림)
-- ARGV  1 칸 이름  2 'set' 또는 'clear'  3 새 값(set 일 때)  4 바꾼 사람  5 확인을 받았으면 '1'  6 이력 최대 건수  7 이력 보존(ms)
-- 반환  전 값(없었으면 false)

local before = redis.call('HGET', KEYS[1], ARGV[1])
if ARGV[2] == 'set' then
    redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
else
    redis.call('HDEL', KEYS[1], ARGV[1])
end

local entry = {'by', ARGV[4], 'field', ARGV[1], 'confirmed', ARGV[5]}
if before then
    table.insert(entry, 'from')
    table.insert(entry, before)
end
if ARGV[2] == 'set' then
    table.insert(entry, 'to')
    table.insert(entry, ARGV[3])
end
local id = redis.call('XADD', KEYS[2], 'MAXLEN', ARGV[6], '*', unpack(entry))
local at = tonumber(string.match(id, '^(%d+)'))
redis.call('XTRIM', KEYS[2], 'MINID', math.max(0, at - tonumber(ARGV[7])))
return before
