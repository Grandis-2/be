-- 입장 커서 위에서 기다리는 인원과 커서를 함께 읽는다. 따로 읽으면 그 사이 배분이 커서를 올려 수가 어긋난다.
-- KEYS  1 queue  2 admitted
-- 반환  {기다리는 인원, 커서('-1' = 아직 없음)}

local admitted = tonumber(redis.call('GET', KEYS[2]) or -1)
if admitted == nil or admitted ~= admitted or admitted == math.huge or admitted == -math.huge then
    admitted = -1
end
local from = admitted >= 0 and ('(' .. string.format('%.0f', admitted)) or '-inf'
return {redis.call('ZCOUNT', KEYS[1], from, '+inf'), string.format('%.0f', admitted)}
