-- 판정 재료와 그것을 읽은 Redis 시각(ms)을 한 번에 읽는다. 노드는 나이를 Redis 시계로 재 노드마다 다르게 낡지 않는다.
-- KEYS  1 snapshot
-- 반환  {지금(ms), 필드1, 값1, ...}

local entries = redis.call('HGETALL', KEYS[1])
local t = redis.call('TIME')
local now = string.format('%.0f', tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000))
table.insert(entries, 1, now)
return entries
