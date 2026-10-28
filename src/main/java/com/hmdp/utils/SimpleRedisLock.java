package com.hmdp.utils;

import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;

public class SimpleRedisLock implements ILock {
    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String LOCK_KEY_PREFIX = "lock:";
    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }
    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标示
        long threadId = Thread.currentThread().getId();
        // 尝试获取锁
        String key = LOCK_KEY_PREFIX + name;
        String value = threadId + "";
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, value, timeoutSec, TimeUnit.SECONDS);
        return  Boolean.TRUE.equals(result);
    }

    @Override
    public void unlock() {
        // 解锁
        stringRedisTemplate.delete(LOCK_KEY_PREFIX + name);
    }
}
