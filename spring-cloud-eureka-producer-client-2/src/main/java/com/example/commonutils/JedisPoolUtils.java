package com.example.commonutils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@PropertySource("classpath:application.properties")
public class JedisPoolUtils {
    private static JedisPool pool = null;
    @Value("${spring.redis.maxIdle}")
    private static int maxIdle = 30;
    @Value("${spring.redis.minIdle}")
    private static int minIdle = 10;
    @Value("${spring.redis.maxTotal}")
    private static int maxTotal = 100;
    @Value("${spring.redis.host}")
    private static String host = "10.160.109.63";
    @Value("${spring.redis.timeout}")
    private static int timeout = 1000;
    @Value("${spring.redis.database}")
    private static int database = 1;
    @Value("${spring.redis.port}")
    private static int port = 6379;
    @Value("${spring.redis.password}")
    private static String password = "lidata429";
    static {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxIdle(maxIdle);// 最大闲置个数
        poolConfig.setMinIdle(minIdle);// 最小闲置个数
        poolConfig.setMaxTotal(maxTotal);// 最大连接数
        pool = new JedisPool(poolConfig, host, port,timeout,password,database);
    }

    public static Jedis getJedis() {
        return pool.getResource();
    }
}
