-- 입장 기록을 지운다. 접수 쪽이 그 입장의 입장권을 더 받지 않을 때(이미 썼거나 마지막 접수 이전 발급) 다음 진입이
-- 새로 판정되게. 거절된 입장권이 이 입장 기록에서 나온 것일 때만 지운다 — 다른 탭의 옛 입장권이 지금 입장을 지우지 않게.
-- KEYS  1 grace
-- ARGV  1 customerId  2 거절된 입장권의 만료(초)  3 입장권 창(초)  4 입장권 수명(초)
-- 반환  1 지웠다 · 0 지울 입장 기록이 없거나 다른 입장이다

local exp = tonumber(ARGV[2])
local window = tonumber(ARGV[3])
local ttl = tonumber(ARGV[4])
if exp == nil or window == nil or window < 1 or ttl == nil then
    return redis.error_reply('만료 · 창 · 수명은 수여야 한다')
end
local record = redis.call('HGET', KEYS[1], ARGV[1])
if type(record) ~= 'string' or string.sub(record, 1, 2) ~= 'a:' then
    return 0
end
local at = tonumber(string.sub(record, 3))
if at == nil or math.floor(at / window) * window + ttl ~= exp then
    return 0
end
redis.call('HDEL', KEYS[1], ARGV[1])
return 1
