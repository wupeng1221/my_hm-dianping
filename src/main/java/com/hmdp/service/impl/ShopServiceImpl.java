package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getShopById(Long id) {
        CacheClient cacheClient = new CacheClient(stringRedisTemplate);
        //储存空值解决，缓存穿透
        //Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //互斥锁解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /*public Shop queryWithMutex(Long id) {
        //存储空值，解决缓存穿透
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //查询redis中是否存在缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {//只有字符串返回true,"",null,转义都是false
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断redis中是否是空值
        if (shopJson != null) {
            //不是null,说明此处是""
            return null;
        }
        //未命中（不是数据也不是空值），根据id查询
        //实现缓存重建
        //1.获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean hasLock = tryLock(lockKey);
            //2.判断是否获取成功
            if (!hasLock) {
                //3.失败休眠并重试
                Thread.sleep(50);
                queryWithMutex(id);
            }
            //4.获取成功根据id查询,redis写入数据
            //模拟缓存重建的延迟
            Thread.sleep(200);
            shop = baseMapper.selectOne(new LambdaQueryWrapper<Shop>().eq(Shop::getId, id));
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            //5.释放互斥锁
            unlock(lockKey);
        }
        return shop;
    }*/

    /*public Shop queryWithPassThrough(Long id) {
        //存储空值，解决缓存穿透
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //查询redis中是否存在缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {//只有字符串返回true,"",null,转义都是false
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断redis中是否是空缓存
        if (shopJson != null) {
            //不是null,说明此处是""
            return null;
        }
        //不存在，根据id查询
        Shop shop = baseMapper.selectOne(new LambdaQueryWrapper<Shop>().eq(Shop::getId, id));
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }*/

    /*private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
    public void saveShop2Redis(Long id, Long expireSeconds) {
        //1.查询店铺数据
        Shop shop = getById(id);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
    //开一个线程池
    private static final ExecutorService CACHE_REBUILD_POOL = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id) {
        //存储空值，解决缓存穿透
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //查询redis中是否存在缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)) {//只有字符串返回true,"",null,转义都是false
            //未命中直接返回null
            return null;
        }
        //命中，查看是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = (Shop) redisData.getData();
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回店铺信息
            return shop;
        }
        //过期，需要缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean hasLock = tryLock(lockKey);
        if (hasLock) {
            CACHE_REBUILD_POOL.submit(()->{
                try {
                    saveShop2Redis(id, 30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return shop;
    }*/

    @Transactional
    @Override
    public Result updateShop(Shop shop) {
        //首先更新数据库，再删除缓存，如果先删除缓存的话，此时缓存没有命中，查出来的数据是旧数据
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();

    }
}
