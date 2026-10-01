-- 리더 리스를 놓는다. 내 것일 때만 지운다 — 남이 잡은 리스를 지우면 두 리더가 생긴다.
-- KEYS  1 leader
-- ARGV  1 ownerId
-- 반환  지웠으면 1, 아니면 0

local current = redis.call('GET', KEYS[1])
if not current then
    return 0
end
local sep = string.find(current, '|', 1, true)
local owner = sep == nil and current or string.sub(current, sep + 1)
if owner == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
