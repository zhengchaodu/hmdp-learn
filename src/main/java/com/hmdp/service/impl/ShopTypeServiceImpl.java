package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        // 1.在redis中查询商铺类型列表
        String typeListJson = stringRedisTemplate.opsForValue().get(SHOP_GEO_KEY);
        // 2.存在，则直接返回
        if (typeListJson != null) {
            List<ShopType> shopTypeList = JSONUtil.toList(typeListJson, ShopType.class);
            return Result.ok(shopTypeList);
        }
        // 3.不存在，则查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        // 4.不存在，则返回错误
        if (shopTypeList == null || shopTypeList.isEmpty()) {
            return Result.fail("商铺类型不存在");
        }
        // 5.存在，则写入redis
        stringRedisTemplate.opsForValue().set(SHOP_GEO_KEY, JSONUtil.toJsonStr(shopTypeList), RedisConstants.CACHE_SHOP_TTL, java.util.concurrent.TimeUnit.MINUTES);
        // 6.返回
        return Result.ok(shopTypeList);
    }
}
