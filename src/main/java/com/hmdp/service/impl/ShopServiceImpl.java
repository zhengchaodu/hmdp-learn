package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.log.Log;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.apache.tomcat.jni.Time;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Resource
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) {
        // 使用缓存工具类
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // Shop shop = cacheClient.queryWithLogicExpire(CACHE_SHOP_KEY, id, Shop.class,LOCK_SHOP_KEY, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 原方法：
        // 1.缓存穿透方法
        // Shop shop = queryWithPassThrough(id);
        // 2.使用互斥锁解决缓存击穿
        // Shop shop = queryWithMutex_Func1(id);
        // 3.逻辑过期解决缓存击穿问题
        // Shop shop = queryWithLogicExpire(id);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        // 7.返回
        return Result.ok(shop);
    }
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        // 1. 判断是否存在
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }
        // 2. 更新数据库
        updateById(shop);
        // 3. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        // TODO 延时双删策略
        // 我们的是一个单体项目，所以更新数据库和删除缓存在同一段代码里
        // 如果是分布式系统，这里可能就通过MQ通知另一个系统来删除缓存
        return Result.ok();
    }
}
