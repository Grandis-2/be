-- 회차 일정을 반영한다. 표준 큐라 순서가 뒤집혀 오므로 일정 번호가 지금 것보다 클 때만 쓴다(같은 번호는 재전송이다).
-- 은퇴 표식("retired|번호|시각")의 번호도 지금 번호로 본다 — 재전달된 옛 일정이 끝난 회차를 다시 열지 않게.
-- KEYS  1 products
-- ARGV  1 productId  2 일정 "opensAt(ms)|closesAt(ms)|scheduleVersion"  3 scheduleVersion
-- 반환  1 썼다 · 0 같거나 옛 번호라 버렸다

local version = tonumber(ARGV[3])
if version == nil or version ~= math.floor(version) or version < 1 then
    return redis.error_reply('일정 번호는 1 이상 정수여야 한다: ' .. tostring(ARGV[3]))
end
local current = redis.call('HGET', KEYS[1], ARGV[1])
if current then
    -- 깨진 값은 번호를 모르는 것이라 새 일정으로 덮는다
    local seen = tonumber(string.match(current, '^retired|(%d+)|') or string.match(current, '|(%d+)$'))
    if seen ~= nil and seen >= version then
        return 0
    end
end
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
return 1
