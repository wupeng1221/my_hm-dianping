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
        // todo 本地ip更新的同时需要修改配置类
        // todo 建议写在配置文件中，不要写死
        //配置类
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://10.17.220.216:6379")
                .setPassword("wupeng1221");
        return Redisson.create(config);
    }

}
