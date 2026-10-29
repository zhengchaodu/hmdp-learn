package com.hmdp.config;

import org.redisson.api.RedissonClient;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient() {
        // 配置类
        Config config = new Config();
        // 添加redis地和密码
        // 这里添加了单点的地址，也可以使用config.useClusterServers()来配置集群的地址
        config.useSingleServer().setAddress("redis://localhost:6379").setPassword("123456");
        // 创建客户端
        return Redisson.create(config);
    }
    @Bean
    public RedissonClient redissonClient1() {
        // 配置类
        Config config = new Config();
        // 添加redis地和密码
        // 这里添加了单点的地址，也可以使用config.useClusterServers()来配置集群的地址
        config.useSingleServer().setAddress("redis://localhost:6380").setPassword("123456");
        // 创建客户端
        return Redisson.create(config);
    }
    // ... multi-lock还可以配置更多的锁，解决分布式锁主从一致性问题
}
