package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

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
    public List<ShopType> queryTypeList() {
        // 先从Redis查询，如果存在，则返回，否则从数据库中查询并缓存到Redis中
        List<ShopType> shopTypeList = new ArrayList<>();
        Set<String> jsonSet = stringRedisTemplate.opsForZSet().range(CACHE_SHOP_TYPE_KEY, 0, -1);
        if ( jsonSet != null && !jsonSet.isEmpty()) {
            // 存在，则返回
            for (String json : jsonSet) {
                ShopType shopType = JSONUtil.toBean(json, ShopType.class);
                shopTypeList.add(shopType);
            }
            return shopTypeList;
        }
        // 不存在，则从数据库中查询
        shopTypeList = query().orderByAsc("sort").list();
        if (shopTypeList == null) {
            return null;
        }
        // 缓存到Redis中
        for (ShopType shopType : shopTypeList) {
            stringRedisTemplate.opsForZSet().add(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(shopType), shopType.getSort());
        }
        stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        return shopTypeList;
    }
}
