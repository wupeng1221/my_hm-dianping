package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.text.DateFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final long BEGIN_TIMESTAMP = 1672531200L;
    private static final int COUNT_BIT = 32;
    public long nextId(String keyPrefix) {
        //参数：业务的前缀
        //生成时间戳31位（秒）
        LocalDateTime now = LocalDateTime.now();
        long timestamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        //序列号32位，最好拼接上日期，为了防止所有的业务都共用一个key可能会用尽达到上限，而且方便统计
        //此时一天一个key，因为redis以冒号为层级分割
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        //拼接
        return timestamp << COUNT_BIT | count;
    }
}
