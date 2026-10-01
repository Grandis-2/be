-- 순서 조회. 조회 · 생존 신호 · 입장 판정을 한 번에 한다 — 나누면 같은 사람이 두 번 입장하거나 성실한 사람이 걷힌다.
-- KEYS  1 queue  2 admitted  3 alive  4 grace
-- ARGV  1 customerId  2 alive TTL(초)  3 지금(초)  4 입장권 수명(초)  5 입장권 창(초)  6 이탈 기록 보관(초)
--       입장권이 만료된 입장, 보관 기간이 지난 입장 미통지는 끝난 것으로 본다
-- 반환  {상태, 앞 인원, score, 총원, 입장 시각(초)} · 상태는 WAITING | ADMITTED | NOT_QUEUED, 모르는 값은 -1 · '-1'

local ttl = tonumber(ARGV[2])
if ttl == nil or ttl < 1 or ttl ~= math.floor(ttl) then
    return redis.error_reply('alive TTL 은 양의 정수여야 한다: ' .. tostring(ARGV[2]))
end
local now = tonumber(ARGV[3])
if now == nil or now < 0 or now ~= now or now == math.huge then
    return redis.error_reply('시각은 0 이상 유한해야 한다: ' .. tostring(ARGV[3]))
end
local ticketTtl = tonumber(ARGV[4])
if ticketTtl == nil or ticketTtl < 1 or ticketTtl ~= math.floor(ticketTtl) then
    return redis.error_reply('입장권 수명은 양의 정수여야 한다: ' .. tostring(ARGV[4]))
end
local ticketWindow = tonumber(ARGV[5])
if ticketWindow == nil or ticketWindow < 1 or ticketWindow ~= math.floor(ticketWindow) then
    return redis.error_reply('입장권 창은 양의 정수여야 한다: ' .. tostring(ARGV[5]))
end
local retention = tonumber(ARGV[6])
if retention == nil or retention < 1 or retention ~= math.floor(retention) then
    return redis.error_reply('보관 기간은 양의 정수여야 한다: ' .. tostring(ARGV[6]))
end
local stamp = string.format('%.0f', now)

local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not score then
    -- 입장하면 줄에서 빠지므로, 입장 표시로 이미 입장한 사람을 알아본다. 입장 시각을 돌려줘 입장권이 매번 같게 한다
    local grace = redis.call('HGET', KEYS[4], ARGV[1])
    if type(grace) == 'string' and string.sub(grace, 1, 2) == 'a:' then
        local at = tonumber(string.sub(grace, 3))
        if at ~= nil and now < math.floor(at / ticketWindow) * ticketWindow + ticketTtl then
            return {'ADMITTED', 0, '-1', -1, string.sub(grace, 3)}
        end
        return {'NOT_QUEUED', -1, '-1', -1, '-1'}
    end
    -- 청소가 옮긴, 아직 못 알린 입장이다. 지금 알리므로 입장 표시로 바꾼다
    if type(grace) == 'string' and string.sub(grace, 1, 2) == 'r:' then
        local at = tonumber(string.sub(grace, 3))
        if at ~= nil and at >= now - retention then
            redis.call('HSET', KEYS[4], ARGV[1], 'a:' .. stamp)
            return {'ADMITTED', 0, '-1', -1, stamp}
        end
    end
    return {'NOT_QUEUED', -1, '-1', -1, '-1'}
end

redis.call('ZADD', KEYS[3], now + ttl, ARGV[1])

local admitted = tonumber(redis.call('GET', KEYS[2]) or -1)
if admitted == nil or admitted ~= admitted or admitted == math.huge or admitted == -math.huge then
    admitted = -1
end
if admitted >= 0 and tonumber(score) <= admitted then
    redis.call('ZREM', KEYS[1], ARGV[1])
    redis.call('ZREM', KEYS[3], ARGV[1])
    redis.call('HSET', KEYS[4], ARGV[1], 'a:' .. stamp)
    return {'ADMITTED', 0, score, -1, stamp}
end

-- 앞 인원과 총원을 같은 기준(입장 커서 위)으로 센다. 다르면 "앞에 100명인데 총 80명" 이 나온다
local from = admitted >= 0 and ('(' .. string.format('%.0f', admitted)) or '-inf'
local rank = redis.call('ZCOUNT', KEYS[1], from, '(' .. score)
local total = redis.call('ZCOUNT', KEYS[1], from, '+inf')
return {'WAITING', rank, score, total, '-1'}
