-- 比较线程标示与锁中的线程标示是否一致
if(redis.call('get',KEYS[1]) == ARGV[1]) then
    -- 如果一致，删除锁
    return redis.call('del',KEYS[1])
else
    -- 如果不一致，返回0表示释放锁失败
    return 0
end
