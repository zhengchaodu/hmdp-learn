package com.hmdp.utils;

public interface ILock {
    /**
     * 尝试获取锁
     * @param timeoutSec 锁的超时时间，单位秒
     * @return 是否成功获取锁
     */
    boolean tryLock(long timeoutSec);
    /**
     * 解锁
     */
    void unlock();
}
