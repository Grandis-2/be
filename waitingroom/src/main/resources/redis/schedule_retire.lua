-- 끝난 지 오래된 모델의 일정을 은퇴 표식("retired|번호|시각")으로 바꾼다. 번호를 남겨, 늦게 재전달된 옛 일정이
-- 끝난 회차를 다시 열지 못하게 한다. 읽은 뒤 새 일정이 왔으면(값이 다르면) 바꾸지 않는다.
-- KEYS  1 products
-- ARGV  1 productId  2 읽었던 일정 값  3 그 일정 번호  4 지금(ms)
-- 반환  1 바꿨다 · 0 그사이 바뀌었거나 없다

if redis.call('HGET', KEYS[1], ARGV[1]) == ARGV[2] then
    redis.call('HSET', KEYS[1], ARGV[1], 'retired|' .. ARGV[3] .. '|' .. ARGV[4])
    return 1
end
return 0
