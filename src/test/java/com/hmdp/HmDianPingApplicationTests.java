package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

}
