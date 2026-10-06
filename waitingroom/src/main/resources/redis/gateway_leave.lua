-- 종료하는 노드를 분모에서 바로 뺀다. 안 빼면 임계가 지날 때까지 남은 노드가 작은 몫을 쓴다.
-- KEYS  1 gateways
-- ARGV  1 노드 ID
-- 반환  지운 필드 수

if ARGV[1] == nil or ARGV[1] == '' or string.sub(ARGV[1], 1, 1) == '#' then
    return redis.error_reply('노드 ID 가 비었거나 # 로 시작한다')
end
return redis.call('HDEL', KEYS[1], ARGV[1], '#p:' .. ARGV[1], '#r:' .. ARGV[1])
