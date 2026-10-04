-- 줄 서기. 이미 서 있으면 그 순서를 지키고, 새로 서면 순서 값이 뒤로 가지 않게 바닥을 깐다. 이미 입장했으면 입장 시각을 돌려준다.
-- KEYS  1 queue  2 maxscore  3 alive  4 admitted  5 grace
-- ARGV  1 customerId  2 maxscore TTL(초)  3 alive TTL(초)  4 줄 상한(-1 = 제한 없음)  5 지금(초)  6 이탈 기록 보관(초)  7 입장권 수명(초)  8 입장권 창(초)
-- 반환  {score, 바닥 적용, 이미 섰음, 앞 인원, 다시 섬, 입장 시각('-1' = 입장 아님)} · 상한에 걸리면 score '-1'

local function positiveInt(value, name)
    local n = tonumber(value)
    if n == nil or n < 1 or n ~= math.floor(n) then
        return nil, name .. ' 은 양의 정수여야 한다: ' .. tostring(value)
    end
    return n
end

-- 쓰기 전에 인자를 모두 검증한다. Lua 는 중간 오류를 되돌리지 않는다
local scoreTtl, err = positiveInt(ARGV[2], 'maxscore TTL')
if not scoreTtl then return redis.error_reply(err) end
local aliveTtl
aliveTtl, err = positiveInt(ARGV[3], 'alive TTL')
if not aliveTtl then return redis.error_reply(err) end
local now = tonumber(ARGV[5])
if now == nil or now < 0 or now ~= now or now == math.huge then
    return redis.error_reply('시각은 0 이상 유한해야 한다: ' .. tostring(ARGV[5]))
end
local retention
retention, err = positiveInt(ARGV[6], '이탈 기록 보관 기간')
if not retention then return redis.error_reply(err) end
local ticketTtl
ticketTtl, err = positiveInt(ARGV[7], '입장권 수명')
if not ticketTtl then return redis.error_reply(err) end
local ticketWindow
ticketWindow, err = positiveInt(ARGV[8], '입장권 창')
if not ticketWindow then return redis.error_reply(err) end
local maxLen = tonumber(ARGV[4])
if maxLen == nil or maxLen < -1 or maxLen ~= math.floor(maxLen) then
    return redis.error_reply('줄 상한은 -1 이상 정수여야 한다: ' .. tostring(ARGV[4]))
end

-- 앞 인원은 입장 커서 위에서 센다(queue_status 와 같은 기준). 깨진 커서는 아직 아무도 안 들어온 것으로 본다
local admitted = tonumber(redis.call('GET', KEYS[4]) or -1)
if admitted == nil or admitted ~= admitted or admitted == math.huge or admitted == -math.huge then
    admitted = -1
end
local from = admitted >= 0 and ('(' .. string.format('%.0f', admitted)) or '-inf'

local stamp = string.format('%.0f', now)

-- 이미 입장한 사람(입장권이 아직 살아 있는 동안)은 다시 세우지 않고 같은 입장 시각을 돌려준다 — 입장권이 매번 같다.
-- 입장권 만료는 발급 식과 같다: 창 시작 + 수명
local record = redis.call('HGET', KEYS[5], ARGV[1])
if type(record) == 'string' and string.sub(record, 1, 2) == 'a:' then
    local at = tonumber(string.sub(record, 3))
    if at ~= nil and now < math.floor(at / ticketWindow) * ticketWindow + ticketTtl then
        return {'-1', 0, 1, 0, 0, string.sub(record, 3)}
    end
end

-- 이미 줄에 있으면 그 순서를 돌려준다. 상한 검사보다 앞이다 — 이미 선 사람이 상한 때문에 자리를 잃지 않게.
-- 커서 아래면 차례가 온 것이다. 조회처럼 줄에서 빼고 지금을 입장 시각으로 남긴다
local existing = redis.call('ZSCORE', KEYS[1], ARGV[1])
if existing then
    if admitted >= 0 and tonumber(existing) <= admitted then
        redis.call('ZREM', KEYS[1], ARGV[1])
        redis.call('ZREM', KEYS[3], ARGV[1])
        redis.call('HSET', KEYS[5], ARGV[1], 'a:' .. stamp)
        return {'-1', 0, 1, 0, 0, stamp}
    end
    redis.call('ZADD', KEYS[3], now + aliveTtl, ARGV[1])
    return {existing, 0, 1, redis.call('ZCOUNT', KEYS[1], from, '(' .. existing), 0, '-1'}
end

-- 청소가 옮긴, 입장을 못 알린 사람이다. 지금 알린다 — 맨 뒤로 보내면 줄 선 사람 전원에게 밀린다
if type(record) == 'string' and string.sub(record, 1, 2) == 'r:' then
    local at = tonumber(string.sub(record, 3))
    if at ~= nil and at >= now - retention then
        redis.call('HSET', KEYS[5], ARGV[1], 'a:' .. stamp)
        return {'-1', 0, 1, 0, 0, stamp}
    end
end

-- 상한은 기다리는 사람만 센다(입장한 사람은 조회해 올 때까지 줄에 남아 있다)
if maxLen >= 0 and redis.call('ZCOUNT', KEYS[1], from, '+inf') >= maxLen then
    return {'-1', 0, 0, -1, 0, '-1'}
end

-- 순서 값은 Redis 시계(마이크로초)다. 시계가 뒤로 가도 바닥값 · 줄의 맨 뒤 · 입장 커서 위에 세워 추월이 없다
local t = redis.call('TIME')
local score = tonumber(t[1]) * 1000000 + tonumber(t[2])
local floor = tonumber(redis.call('GET', KEYS[2]) or 0)
local last = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
if last[2] ~= nil and tonumber(last[2]) > floor then
    floor = tonumber(last[2])
end
local applied = 0
if floor >= score then
    score = floor + 1
    applied = 1
end
if admitted >= 0 and admitted >= score then
    score = math.floor(admitted) + 1
    applied = 1
end

-- %.0f 로 적는다. Lua 5.1 의 기본 변환은 16자리 마이크로초를 과학 표기로 접어 순서가 바뀐다
redis.call('ZADD', KEYS[1], string.format('%.0f', score), ARGV[1])
redis.call('SET', KEYS[2], string.format('%.0f', score), 'EX', scoreTtl)
redis.call('ZADD', KEYS[3], now + aliveTtl, ARGV[1])
local rank = redis.call('ZCOUNT', KEYS[1], from, '(' .. string.format('%.0f', score))

-- 이탈 기록은 재방문으로 세고, 끝난 입장 표시와 보관이 지난 입장 미통지는 새로 섰으니 지운다
local rejoined = 0
if type(record) == 'string' then
    local at = tonumber(string.sub(record, 3))
    if string.sub(record, 1, 2) == 'd:' and at ~= nil and at >= now - retention then
        rejoined = 1
    end
    redis.call('HDEL', KEYS[5], ARGV[1])
end
return {string.format('%.0f', score), applied, 0, rank, rejoined, '-1'}
