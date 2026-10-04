-- 판정 재료(스냅샷) 발행. 통째로 갈아 끼우되 키가 비는 순간이 없게 먼저 덮어쓰고 남은 필드를 지운다.
-- KEYS  1 snapshot  2 snapshot fence
-- ARGV  1 임기(0 이면 리더 아님)  2 울타리 수명(ms)  3.. 필드 · 값 쌍
-- 반환  {실린 필드 수, 지운 필드 수} · 옛 임기면 {-1, -1, 막은 임기}

local fence = tonumber(ARGV[1])
if fence == nil or fence ~= fence or fence ~= math.floor(fence) then
    return redis.error_reply('펜스 번호는 정수여야 한다: ' .. tostring(ARGV[1]))
end
local fenceTtl = tonumber(ARGV[2])
if fenceTtl == nil or fenceTtl ~= fenceTtl or fenceTtl < 1 or fenceTtl ~= math.floor(fenceTtl) then
    return redis.error_reply('울타리 수명은 1 이상 정수여야 한다: ' .. tostring(ARGV[2]))
end
table.remove(ARGV, 1)
table.remove(ARGV, 1)
if #ARGV == 0 or #ARGV % 2 ~= 0 then
    return redis.error_reply('필드와 값은 짝을 이뤄야 한다: ' .. #ARGV)
end
if fence <= 0 then
    return {-1, -1, 0}
end
-- 옛 임기의 발행은 안 듣는다 — 옛 시야로 덮으면 전 노드의 대기 수가 뒤로 가 줄이 없는 것으로 읽힌다
local seen = tonumber(redis.call('GET', KEYS[2]))
if seen ~= nil and seen == seen and fence < seen then
    return {-1, -1, seen}
end
redis.call('SET', KEYS[2], string.format('%.0f', fence), 'PX', fenceTtl)

local keep = {}
for i = 1, #ARGV, 2 do
    keep[ARGV[i]] = true
end
-- 나눠 쓴다 — Lua 5.1 의 unpack 은 결과 수 한도(약 8000)가 있어 모델이 많으면 통째로는 실패한다
local CHUNK = 512
for i = 1, #ARGV, CHUNK * 2 do
    redis.call('HSET', KEYS[1], unpack(ARGV, i, math.min(i + CHUNK * 2 - 1, #ARGV)))
end

local stale = 0
local chunk = {}
for _, field in ipairs(redis.call('HKEYS', KEYS[1])) do
    if not keep[field] then
        stale = stale + 1
        chunk[#chunk + 1] = field
        if #chunk == CHUNK then
            redis.call('HDEL', KEYS[1], unpack(chunk))
            chunk = {}
        end
    end
end
if #chunk > 0 then
    redis.call('HDEL', KEYS[1], unpack(chunk))
end
return {#ARGV / 2, stale}
