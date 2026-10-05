#!lua flags=allow-oom
-- 마감된 모델의 줄과 생존 신호를 지운다. 되돌릴 수 없는 쓰기라 임기와 Redis 시각으로 쓰기 직전에 한 번 더 막는다.
-- 입장 커서 · 바닥값 · 입장 표시는 남긴다 — 지우면 입장한 사람이 입장권을 못 다시 받고, 재오픈 때 순서가 역행한다.
-- KEYS  1 queue  2 alive  3 closefence  4 grace
-- ARGV  1 임기  2 지움(1) 또는 표만 세움(0)  3 표 수명(ms)  4 지워도 되는 시각(초, 마감 + 유예)  5 입장 표시 수명(초)
-- 반환  1 지웠다 · 0 안 지웠다 · -1 옛 임기라 막았다 · -2 표가 없어 막았다

local fence = tonumber(ARGV[1])
if fence == nil or fence ~= fence or fence ~= math.floor(fence) then
    return redis.error_reply('펜스 번호는 정수여야 한다: ' .. tostring(ARGV[1]))
end
local fenceTtl = tonumber(ARGV[3])
if fenceTtl == nil or fenceTtl < 1 or fenceTtl ~= math.floor(fenceTtl) then
    return redis.error_reply('표 수명은 양의 정수여야 한다: ' .. tostring(ARGV[3]))
end
local graceTtl = tonumber(ARGV[5])
if graceTtl == nil or graceTtl < 1 or graceTtl ~= math.floor(graceTtl) then
    return redis.error_reply('입장 표시 수명은 양의 정수여야 한다: ' .. tostring(ARGV[5]))
end
local after = tonumber(ARGV[4])
if after == nil or after ~= after or after < 0 then
    return redis.error_reply('지워도 되는 시각은 0 이상이어야 한다: ' .. tostring(ARGV[4]))
end
if fence <= 0 then
    return 0
end
local seen = tonumber(redis.call('GET', KEYS[3]))
if seen ~= nil and seen == seen and fence < seen then
    return -1
end
redis.call('SET', KEYS[3], string.format('%.0f', fence), 'PX', fenceTtl)
if ARGV[2] ~= '1' then
    return 0
end
-- 지난 회차에 이 임기(또는 그 앞)가 표를 세운 적이 있어야 지운다. 승계 첫 회차가 바로 지우지 않게
if seen == nil or seen ~= seen then
    return -2
end
if tonumber(redis.call('TIME')[1]) < after then
    return 0
end
redis.call('DEL', KEYS[1], KEYS[2])
-- 입장 표시는 남은 입장권과 미통지 보관이 끝날 만큼만 두고 사라지게 한다(청소는 더 돌지 않는다)
redis.call('EXPIRE', KEYS[4], graceTtl)
return 1
