package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {
    @Bean
    public RedissonClient redissonClient() {
        //配置类
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://192.168.1.100/6379")
                .setPassword("wupeng1221")
                .setDatabase(0);
        return Redisson.create(config);
    }

}
