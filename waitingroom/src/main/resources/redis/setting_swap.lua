-- 운영값 한 칸을 바꾸거나 지우고 그 전 값을 돌려준다. 한 번에 하므로 동시에 바꿔도 감사 로그의 전 값이 맞다.
-- 실제로 바뀐 때만 변경 이력을 같은 스크립트에서 남긴다. 이력은 건수 · 기간 한도 밖을 바로 지운다.
-- Lua 는 뒤 명령이 실패해도 앞 쓰기를 되돌리지 않는다 — 이력 키를 먼저 보고, 쓸 수 없으면 아무것도 바꾸지 않는다.
-- KEYS  1 settings  2 변경 이력(스트림)
-- ARGV  1 칸 이름  2 'set' 또는 'clear'  3 새 값(set 일 때)  4 바꾼 사람  5 확인을 받았으면 '1'  6 이력 최대 건수  7 이력 보존(ms)
-- 반환  전 값(없었으면 false)

local kind = redis.call('TYPE', KEYS[2])['ok']
if kind ~= 'none' and kind ~= 'stream' then
    return redis.error_reply('변경 이력 키가 스트림이 아니다: ' .. kind)
end

local before = redis.call('HGET', KEYS[1], ARGV[1])
local entry = {'by', ARGV[4], 'field', ARGV[1], 'confirmed', ARGV[5]}
if before then
    table.insert(entry, 'from')
    table.insert(entry, before)
end
if ARGV[2] == 'set' then
    if before == ARGV[3] then
        return before
    end
    redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
    table.insert(entry, 'to')
    table.insert(entry, ARGV[3])
else
    if not before then
        return before
    end
    redis.call('HDEL', KEYS[1], ARGV[1])
end

local id = redis.call('XADD', KEYS[2], 'MAXLEN', ARGV[6], '*', unpack(entry))
local at = tonumber(string.match(id, '^(%d+)'))
redis.call('XTRIM', KEYS[2], 'MINID', math.max(0, at - tonumber(ARGV[7])))
return before
