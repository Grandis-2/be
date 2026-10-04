#!lua flags=allow-oom
-- 리더 리스를 잡거나 연장한다. 새로 잡을 때마다 단조 증가하는 임기(펜스 번호)를 매긴다.
-- KEYS  1 leader  2 임기 카운터
-- ARGV  1 ownerId  2 리스(ms)
-- 반환  {잡았음(1/0), 소유자, 남은 리스(ms), 임기(못 잡았으면 0)}

local lease = tonumber(ARGV[2])
if lease == nil or lease < 1 or lease ~= math.floor(lease) then
    return redis.error_reply('리스는 양의 정수여야 한다: ' .. tostring(ARGV[2]))
end
if ARGV[1] == nil or ARGV[1] == '' then
    return redis.error_reply('ownerId 는 필수다')
end

local function ownerOf(value)
    local sep = string.find(value, '|', 1, true)
    if sep == nil then
        return value, 0
    end
    return string.sub(value, sep + 1), tonumber(string.sub(value, 1, sep - 1)) or 0
end

-- 임기는 Redis 시각(마이크로초) + 하루를 바닥으로 깐다. 카운터가 유실돼도 이전 임기보다 작아지지 않는다.
-- 상한에 닿으면 낮춰 깔지 않고 선출을 멈춘다 — 낮추면 이미 나간 큰 임기를 든 옛 리더가 울타리를 통과한다
local MARGIN = 86400000000
local EXACT_MAX = 9007199254740992
local function nextGeneration()
    local t = redis.call('TIME')
    local floor = tonumber(t[1]) * 1000000 + tonumber(t[2]) + MARGIN
    local stored = redis.pcall('GET', KEYS[2])
    local seen = type(stored) == 'string' and tonumber(stored) or nil
    local base = floor
    if seen ~= nil and seen == seen and seen >= floor then
        base = math.ceil(seen)
    end
    if base >= EXACT_MAX - 1 then
        return nil
    end
    if base ~= seen then
        redis.call('SET', KEYS[2], string.format('%.0f', base))
    end
    -- 정수 아닌 표기("5e15" 등)로 고쳐져도 그 값 아래로 내려가지 않게 같은 값을 정수로 다시 적고 센다
    local bumped = redis.pcall('INCR', KEYS[2])
    if type(bumped) == 'table' then
        redis.call('SET', KEYS[2], string.format('%.0f', base))
        bumped = redis.call('INCR', KEYS[2])
    end
    if bumped >= EXACT_MAX then
        return nil
    end
    return bumped
end

local current = redis.call('GET', KEYS[1])
if not current then
    local fence = nextGeneration()
    if fence == nil then
        return redis.error_reply('임기 카운터가 상한이다')
    end
    if redis.call('SET', KEYS[1], string.format('%.0f', fence) .. '|' .. ARGV[1], 'NX', 'PX', lease) then
        return {1, ARGV[1], lease, fence}
    end
    current = redis.call('GET', KEYS[1])
    if not current then
        return {0, '', redis.call('PTTL', KEYS[1]), 0}
    end
    return {0, (ownerOf(current)), redis.call('PTTL', KEYS[1]), 0}
end

local owner, fence = ownerOf(current)
if owner == ARGV[1] then
    if fence <= 0 then
        fence = nextGeneration()
        if fence == nil then
            return redis.error_reply('임기 카운터가 상한이다')
        end
        redis.call('SET', KEYS[1], string.format('%.0f', fence) .. '|' .. ARGV[1], 'PX', lease)
        return {1, ARGV[1], lease, fence}
    end
    redis.call('PEXPIRE', KEYS[1], lease)
    return {1, ARGV[1], lease, fence}
end
return {0, owner, redis.call('PTTL', KEYS[1]), 0}
