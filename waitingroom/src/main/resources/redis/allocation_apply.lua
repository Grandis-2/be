-- 배분 적용. 이번 회차 인원만큼 입장 커서를 올린다. 커서는 뒤로 가지 않고, 옛 임기의 리더는 올리지 못한다.
-- 같은 임기에서 이미 적용한 회차(또는 그 앞 회차)는 다시 올리지 않는다 — 재시도해도 한 회차 몫만 나간다.
-- KEYS  1 queue  2 admitted  3 applyfence  4 applyround
-- ARGV  1 들일 인원(0 이상)  2 임기(펜스, 0 이면 리더 아님)  3 울타리 수명(ms)  4 이 리더가 본 커서 최댓값(모르면 -1)
--       5 회차(리더의 틱 번호, 임기 안에서 늘기만 한다)  6 회차 기록 수명(초)
-- 반환  {커서, 들인 인원, 되살린 폭, 적용함(1/0)} · 울타리에 막히면 {'-1', -1, 막은 임기, 0}

local MAX_ADMIT = 9007199254740992
local admit = tonumber(ARGV[1])
if admit == nil or admit ~= admit or admit < 0 or admit ~= math.floor(admit) or admit > MAX_ADMIT then
    return redis.error_reply('들일 인원은 0 이상 정수여야 한다: ' .. tostring(ARGV[1]))
end
local fence = tonumber(ARGV[2])
if fence == nil or fence ~= fence or fence ~= math.floor(fence) then
    return redis.error_reply('펜스 번호는 정수여야 한다: ' .. tostring(ARGV[2]))
end
local fenceTtl = tonumber(ARGV[3])
if fenceTtl == nil or fenceTtl ~= fenceTtl or fenceTtl < 1 or fenceTtl ~= math.floor(fenceTtl) then
    return redis.error_reply('울타리 수명은 1 이상 정수여야 한다: ' .. tostring(ARGV[3]))
end
local round = tonumber(ARGV[5])
if round == nil or round ~= round or round < 0 or round ~= math.floor(round) then
    return redis.error_reply('회차는 0 이상 정수여야 한다: ' .. tostring(ARGV[5]))
end
local roundTtl = tonumber(ARGV[6])
if roundTtl == nil or roundTtl ~= roundTtl or roundTtl == math.huge or roundTtl < 1 or roundTtl ~= math.floor(roundTtl) then
    return redis.error_reply('회차 기록 수명은 양의 정수여야 한다: ' .. tostring(ARGV[6]))
end
if fence <= 0 then
    return {'-1', -1, '0', 0}
end
-- 옛 임기는 안 들인다 — 승계 뒤 깨어난 옛 리더가 제 몫을 밀면 같은 초에 두 리더의 몫이 다 나간다
local seenFence = tonumber(redis.call('GET', KEYS[3]))
if seenFence ~= nil and seenFence == seenFence and fence < seenFence then
    return {'-1', -1, string.format('%.0f', seenFence), 0}
end

-- 없는 커서와 깨진 커서를 가른다. 깨진 것을 -1 로 접으면 줄 머리부터 다시 세어 이미 들인 사람이 대기로 돌아간다
local raw = redis.call('GET', KEYS[2])
local current = -1
if raw then
    current = tonumber(raw)
    if current == nil or current ~= current or current == math.huge or current == -math.huge then
        return redis.error_reply('입장 커서가 수가 아니다 — 낮추지 않는다: ' .. tostring(raw))
    end
end

-- 이 리더가 쓴 커서가 사라졌으면(복제본 승격 · AOF 잘림) 되살린다. 그대로 두면 들인 사람에게 몫을 또 쓴다
local written = tonumber(ARGV[4])
local healed = false
local healedFrom = ''
if written ~= nil and written == written and written ~= math.huge and written > current then
    local before = current
    current = math.floor(written)
    healedFrom = before < 0 and '-1' or string.format('%.0f', current - before)
    redis.call('SET', KEYS[2], string.format('%.0f', current))
    healed = true
end
if admit > 0 or healed then
    redis.call('SET', KEYS[3], string.format('%.0f', fence), 'PX', fenceTtl)
end
if admit == 0 then
    return {string.format('%.0f', current), 0, healedFrom, 0}
end

-- 회차 기록은 "임기|회차". 같은 임기에서 이 회차 이하는 이미 나갔다. 새 임기는 회차가 작아도 막지 않는다
local applied = redis.call('GET', KEYS[4])
if applied then
    local sep = string.find(applied, '|', 1, true)
    local appliedFence = sep and tonumber(string.sub(applied, 1, sep - 1))
    local appliedRound = sep and tonumber(string.sub(applied, sep + 1))
    if appliedFence == fence and appliedRound ~= nil and round <= appliedRound then
        return {string.format('%.0f', current), 0, healedFrom, 0}
    end
end
-- 줄 조회가 실패하면(스크립트 오류는 앞선 쓰기를 되돌리지 않는다) 회차를 쓴 것으로 남기지 않게, 기록은 조회 뒤에 한다
local function markApplied()
    redis.call('SET', KEYS[4], string.format('%.0f', fence) .. '|' .. string.format('%.0f', round), 'EX', roundTtl)
end

-- 커서 위에서 admit 번째 사람의 순서 값이 새 커서다. 줄이 더 짧으면 맨 뒤 사람까지(이후 도착자는 커서 위에 선다)
local from = current >= 0 and '(' .. string.format('%.0f', current) or '-inf'
local picked = redis.call('ZRANGEBYSCORE', KEYS[1], from, '+inf', 'WITHSCORES', 'LIMIT', admit - 1, 1)
local threshold
if #picked > 0 then
    threshold = tonumber(picked[2])
else
    local last = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
    if #last == 0 then
        markApplied()
        return {string.format('%.0f', current), 0, healedFrom, 1}
    end
    threshold = tonumber(last[2])
end
if threshold <= current then
    markApplied()
    return {string.format('%.0f', current), 0, healedFrom, 1}
end
local exact = string.format('%.0f', threshold)
local entering = redis.call('ZCOUNT', KEYS[1], from, exact)
markApplied()
redis.call('SET', KEYS[2], exact)
return {exact, entering, healedFrom, 1}
