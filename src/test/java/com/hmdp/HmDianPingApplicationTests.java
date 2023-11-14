package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@SpringBootTest
@RunWith(SpringRunner.class)
public class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;

    @Test
    public void testSaveShop() {
        CacheClient cacheClient = new CacheClient(stringRedisTemplate);
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY + 1, shopService.getById(1L), 10L, TimeUnit.SECONDS);
        log.info("end...");
    }

    private final ExecutorService es = Executors.newFixedThreadPool(200);

    @Test
    public void testRedisWorkerId() {

        for (int i = 0; i < 100; i++) {
            System.out.println(redisIdWorker.nextId("order"));
        }
    }

    @Test
    public void loadShopData() {
        //查询店铺信息,这里直接查询所有
        List<Shop> shopList = shopService.list();
        //对shop按照type进行分类，1->美食，2->ktv
        Map<Long, List<Shop>> map = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //按照type分类写入redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //获取类型id
            Long typeId = entry.getKey();
            //获取对应type的店铺
            List<Shop> shops = entry.getValue();
            String key = RedisConstants.SHOP_GEO_KEY + typeId;
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());//实现了iterable接口
            for (Shop shop : shops) {
                //将当前店铺的type的店铺添加到locations集合中
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())));
            }
            //批量写入
            stringRedisTemplate.opsForGeo().add(key, locations);
        }


    }

}
