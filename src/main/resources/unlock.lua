-- 传递的参数第一个是redis中存储的线程标识的key，第二个参数是本地线程标识
-- 获取锁的线程标识
local id = redis.call('GET', KEYS[1])
-- 比较线程标识与锁中的标识是否一致
if (id == ARGV[1]) then
    return redis.call('DEL', KEYS[1])
end
return 0
