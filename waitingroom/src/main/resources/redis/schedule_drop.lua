-- 일정 목록에서 한 칸을 지운다(오래된 은퇴 표식). 읽은 뒤 바뀌었으면(값이 다르면) 지우지 않는다.
-- KEYS  1 products
-- ARGV  1 productId  2 읽었던 값
-- 반환  1 지웠다 · 0 그사이 바뀌었거나 없다

if redis.call('HGET', KEYS[1], ARGV[1]) == ARGV[2] then
    redis.call('HDEL', KEYS[1], ARGV[1])
    return 1
end
return 0
