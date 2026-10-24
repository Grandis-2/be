-- 운영값 한 칸이 읽은 때 그대로일 때만 바꾼다. 판정한 뒤 다른 관리자가 바꿨으면 쓰지 않아 다시 읽고 판정하게 한다.
-- 바꿨으면 변경 이력도 같은 스크립트에서 남긴다. 이력은 건수 · 기간 한도 밖을 바로 지운다.
-- KEYS  1 settings  2 변경 이력(스트림)
-- ARGV  1 칸 이름  2 읽은 때 있었으면 'present', 없었으면 'absent'  3 읽은 값  4 새 값
--       5 바꾼 사람  6 확인을 받았으면 '1'  7 이력 최대 건수  8 이력 보존(ms)
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

local entry = {'by', ARGV[5], 'field', ARGV[1], 'confirmed', ARGV[6]}
if before then
    table.insert(entry, 'from')
    table.insert(entry, before)
end
table.insert(entry, 'to')
table.insert(entry, ARGV[4])
local id = redis.call('XADD', KEYS[2], 'MAXLEN', ARGV[7], '*', unpack(entry))
local at = tonumber(string.match(id, '^(%d+)'))
redis.call('XTRIM', KEYS[2], 'MINID', math.max(0, at - tonumber(ARGV[8])))
return {1, before}
