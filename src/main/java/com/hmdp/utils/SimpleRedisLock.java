package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";
    private static final String THREAD_PREFIX = UUID.fastUUID().toString(true) + "-";
    //使用静态常量与静态代码块完成脚本的初始化
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识
        String threadId = THREAD_PREFIX + Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        //return Boolean.TRUE.equals(success);
        return BooleanUtil.isTrue(success);
    }

    @Override
    public void unlock() {
        /*//获取redis中的线程id
        String idRedis = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        String threadId = THREAD_PREFIX + Thread.currentThread().getId();
        if (idRedis.equals(threadId)) {
            //是同一个线程的锁则释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }*/

        //调用lua脚本实现原子性
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                THREAD_PREFIX + Thread.currentThread().getId()
        );
    }
}
