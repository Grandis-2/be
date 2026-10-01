-- 배분 대상 모델(접수 일정)과 그것을 읽은 Redis 시각(ms)을 한 번에 읽는다.
-- KEYS  1 products
-- 반환  {지금(ms), 모델1, 일정1, ...}

local entries = redis.call('HGETALL', KEYS[1])
local t = redis.call('TIME')
local now = string.format('%.0f', tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000))
table.insert(entries, 1, now)
return entries
