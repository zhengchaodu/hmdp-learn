package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;

public class SimpleRedisLock implements ILock {
    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String LOCK_KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }
    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 尝试获取锁
        String key = LOCK_KEY_PREFIX + name;
        String value = threadId + "";
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, value, timeoutSec, TimeUnit.SECONDS);
        return  Boolean.TRUE.equals(result);
    }

    @Override
    public void unlock() {
        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁的标示
        String key = LOCK_KEY_PREFIX + name;
        String value = stringRedisTemplate.opsForValue().get(key);
        // 判断是否是当前线程的锁
        if (threadId.equals(value)) {
            // 解锁
            stringRedisTemplate.delete(key);
        }
    }
}
