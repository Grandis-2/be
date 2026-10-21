-- 태스크 하트비트. 시각은 Redis 가 찍는다(태스크 시계가 어긋나도 같은 기준으로 잰다).
-- KEYS  1 태스크 표(field = 태스크 ID → "마지막 시각|처음 시각|초당 용량|죽음 판정(초)", 시각은 초)
-- ARGV  1 태스크 ID  2 초당 용량  3 죽음 판정(초)
-- 반환  살아 있는 태스크 수
-- 처음 시각은 처음 알린 때다 — 읽는 쪽이 갓 뜬 태스크의 몫을 낮게 잡는다

local stale = tonumber(ARGV[3])
local capacity = tonumber(ARGV[2])
if not stale or stale < 1 or not capacity or capacity < 1 then
    return redis.error_reply('용량 · 판정은 1 이상이어야 한다')
end
local now = tonumber(redis.call('TIME')[1])
local started = now
local previous = redis.call('HGET', KEYS[1], ARGV[1])
if previous then
    local first = tonumber(string.match(previous, '^%d+|(%d+)|'))
    if first then
        started = first
    end
end
redis.call('HSET', KEYS[1], ARGV[1], now .. '|' .. started .. '|' .. ARGV[2] .. '|' .. ARGV[3])

-- 쓰기와 정리를 한 스크립트에 둔다. 판정은 그 행이 적어 둔 값으로 한다
local alive = 0
local entries = redis.call('HGETALL', KEYS[1])
for i = 1, #entries, 2 do
    local seen, limit = string.match(entries[i + 1], '^(%d+)|%d+|%d+|(%d+)$')
    seen, limit = tonumber(seen), tonumber(limit)
    if not seen or not limit or seen > now or now - seen > limit then
        redis.call('HDEL', KEYS[1], entries[i])
    else
        alive = alive + 1
    end
end
return alive
