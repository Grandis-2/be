-- 이탈자 청소. 생존 신호가 끊긴 대기자를 줄에서 빼 이탈 기록으로 옮기고, 낡은 신호와 기록을 정리한다.
-- 한 번 실행이 하는 일에 상한을 둔다 — Lua 는 도는 동안 다른 요청을 전부 막는다.
-- KEYS  1 queue  2 grace  3 alive  4 admitted  5 applyfence
-- ARGV  1 검사 범위(1..3999)  2 지금(초)  3 기록 보관(초)  4 정리 예산(1..7999)  5 HSCAN 커서  6 앞줄 빼기 허용(1/0)  7 임기
-- 반환  {뺀 대기자, 지운 신호, 지운 기록, 다음 커서, 임기에 막힘(1/0), 못 알린 입장자로 옮긴 수}
-- 기록 값: 'd:<초>' 이탈 · 'a:<초>' 입장 표시 · 'r:<초>' 아직 못 알린 입장

local MAX_SCAN, MAX_BUDGET = 3999, 7999
local limit = tonumber(ARGV[1])
if limit == nil or limit < 1 or limit > MAX_SCAN or limit ~= math.floor(limit) then
    return redis.error_reply('검사 범위는 1..' .. MAX_SCAN .. ' 정수여야 한다: ' .. tostring(ARGV[1]))
end
local now = tonumber(ARGV[2])
if now == nil or now < 0 or now ~= now or now == math.huge then
    return redis.error_reply('시각은 0 이상 유한해야 한다: ' .. tostring(ARGV[2]))
end
local retention = tonumber(ARGV[3])
if retention == nil or retention < 1 or retention ~= math.floor(retention) then
    return redis.error_reply('보관 기간은 양의 정수여야 한다: ' .. tostring(ARGV[3]))
end
local budget = tonumber(ARGV[4])
if budget == nil or budget < 1 or budget > MAX_BUDGET or budget ~= math.floor(budget) then
    return redis.error_reply('정리 예산은 1..' .. MAX_BUDGET .. ' 정수여야 한다: ' .. tostring(ARGV[4]))
end
local cursor = ARGV[5]
if cursor == nil or not string.match(cursor, '^%d+$') then
    return redis.error_reply('커서는 숫자여야 한다: ' .. tostring(cursor))
end
local removeFront = tonumber(ARGV[6])
if removeFront ~= 0 and removeFront ~= 1 then
    return redis.error_reply('앞줄 빼기 허용은 0 또는 1 이어야 한다: ' .. tostring(ARGV[6]))
end
local term = tonumber(ARGV[7])
if term == nil or term ~= term or term ~= math.floor(term) then
    return redis.error_reply('임기는 정수여야 한다: ' .. tostring(ARGV[7]))
end

local function stampOf(value)
    if type(value) ~= 'string' then
        return nil
    end
    local kind = string.sub(value, 1, 2)
    if kind ~= 'd:' and kind ~= 'a:' and kind ~= 'r:' then
        return nil
    end
    local at = tonumber(string.sub(value, 3))
    if at == nil or at ~= at or at == math.huge or at < 0 then
        return nil
    end
    return at
end

-- 옛 임기는 앞줄만 안 뺀다. 정리는 돈다 — 멈추면 기록이 한 방향으로만 자란다
local fenced = tonumber(redis.call('GET', KEYS[5]))
local fencedOut = term <= 0 or (fenced ~= nil and fenced == fenced and term < fenced)
local rawAdmitted = redis.call('GET', KEYS[4])
local admitted = -1
local usable = rawAdmitted == false
if rawAdmitted then
    admitted = tonumber(rawAdmitted)
    usable = admitted ~= nil and admitted == admitted and admitted ~= math.huge and admitted ~= -math.huge
    if not usable then
        admitted = -1
    end
end
-- 생존 신호가 하나도 없으면(Redis 재시작 · 신호 키 유실) 빼지 않는다 — 전원을 이탈로 걷게 된다
local removing = removeFront == 1 and not fencedOut and redis.call('ZCOUNT', KEYS[3], now, '+inf') > 0

-- 커서 위 대기자 중 신호가 끊긴 사람을 이탈로 옮긴다
local swept = 0
if usable and removing then
    local from = admitted >= 0 and ('(' .. string.format('%.0f', math.floor(admitted))) or '-inf'
    local front = redis.call('ZRANGEBYSCORE', KEYS[1], from, '+inf', 'LIMIT', 0, limit)
    if #front > 0 then
        local scores = redis.call('ZMSCORE', KEYS[3], unpack(front))
        local gone, records = {}, {}
        for i = 1, #front do
            local at = tonumber(scores[i])
            if at == nil or at < now then
                gone[#gone + 1] = front[i]
                records[#records + 1] = front[i]
                records[#records + 1] = 'd:' .. string.format('%.0f', now)
            end
        end
        if #gone > 0 then
            redis.call('HSET', KEYS[2], unpack(records))
            redis.call('ZREM', KEYS[1], unpack(gone))
            swept = #gone
        end
    end
end

-- 커서 아래(입장했지만 조회해 오지 않은 사람) 중 신호가 끊긴 사람은 '못 알린 입장' 으로 옮긴다
local reaped = 0
if usable and admitted >= 0 and removing then
    local below = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', string.format('%.0f', math.floor(admitted)),
            'LIMIT', 0, limit)
    if #below > 0 then
        local belowAt = redis.call('ZMSCORE', KEYS[3], unpack(below))
        local moved, marks = {}, {}
        for i = 1, #below do
            local at = tonumber(belowAt[i])
            if at == nil or at < now then
                moved[#moved + 1] = below[i]
                marks[#marks + 1] = below[i]
                marks[#marks + 1] = 'r:' .. string.format('%.0f', now)
            end
        end
        if #moved > 0 then
            redis.call('HSET', KEYS[2], unpack(marks))
            redis.call('ZREM', KEYS[1], unpack(moved))
            reaped = #moved
        end
    end
end

local staleSignals = redis.call('ZRANGE', KEYS[3], '-inf', '(' .. now, 'BYSCORE', 'LIMIT', 0, budget)
if #staleSignals > 0 then
    redis.call('ZREM', KEYS[3], unpack(staleSignals))
end

local scanned = redis.call('HSCAN', KEYS[2], cursor, 'COUNT', budget)
local fields = scanned[2]
local cutoff = now - retention
local doomed = {}
for i = 1, #fields, 2 do
    local at = stampOf(fields[i + 1])
    if (at == nil or at < cutoff) and #doomed < budget then
        doomed[#doomed + 1] = fields[i]
    end
end
if #doomed > 0 then
    redis.call('HDEL', KEYS[2], unpack(doomed))
end
return {swept, #staleSignals, #doomed, scanned[1], fencedOut and 1 or 0, reaped}
