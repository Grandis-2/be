#!lua flags=allow-oom
-- 게이트웨이 하트비트. 시각은 Redis 가 찍는다(노드 시계가 어긋나도 살아 있음을 같은 기준으로 잰다).
-- KEYS  1 gateways (field = 노드 → 마지막 시각(초), '#p:'..노드 → 직전 1초 한산 통과 수)
-- ARGV  1 노드 ID  2 죽은 노드 임계(초)  3 한산 통과 수를 믿는 신선도(초)  4 직전 1초 한산 통과 수
-- 반환  {살아 있는 노드 수, 지금(초), 신선한 노드들의 한산 통과 합}

local PASS = '#p:'
local reapAfter = tonumber(ARGV[2])
if reapAfter == nil or reapAfter < 1 or reapAfter > 86400 or reapAfter ~= math.floor(reapAfter) then
    return redis.error_reply('임계는 1..86400 정수여야 한다: ' .. tostring(ARGV[2]))
end
local fresh = tonumber(ARGV[3])
if fresh == nil or fresh < 1 or fresh > reapAfter or fresh ~= math.floor(fresh) then
    return redis.error_reply('신선도는 1..임계 정수여야 한다: ' .. tostring(ARGV[3]))
end
if ARGV[1] == nil or ARGV[1] == '' or string.sub(ARGV[1], 1, 1) == '#' then
    return redis.error_reply('노드 ID 가 비었거나 # 로 시작한다')
end
local passed = tonumber(ARGV[4])
if passed == nil or passed ~= passed or passed < 0 or passed > 1e9 or passed ~= math.floor(passed) then
    return redis.error_reply('한산 통과 수는 0..1e9 정수여야 한다: ' .. tostring(ARGV[4]))
end

local now = tonumber(redis.call('TIME')[1])
redis.call('HSET', KEYS[1], ARGV[1], now, PASS .. ARGV[1], passed)

-- 쓰기와 정리를 한 스크립트에 둔다. 나누면 그 사이 다른 노드가 방금 지운 노드를 산 것으로 센다
local seenOf, passOf, ids = {}, {}, {}
local entries = redis.call('HGETALL', KEYS[1])
for i = 1, #entries, 2 do
    local field = entries[i]
    if string.sub(field, 1, #PASS) == PASS then
        passOf[string.sub(field, #PASS + 1)] = tonumber(entries[i + 1])
    else
        seenOf[field] = tonumber(entries[i + 1])
        ids[#ids + 1] = field
    end
end
local alive, passSum, dead = 0, 0, {}
for _, id in ipairs(ids) do
    local seen = seenOf[id]
    if seen == nil or seen > now or now - seen > reapAfter then
        dead[#dead + 1] = id
        dead[#dead + 1] = PASS .. id
    else
        alive = alive + 1
        if now - seen <= fresh and passOf[id] ~= nil then
            passSum = passSum + passOf[id]
        end
    end
end
if #dead > 0 then
    redis.call('HDEL', KEYS[1], unpack(dead))
end
return {alive, now, passSum}
