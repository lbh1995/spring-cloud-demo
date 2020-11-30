package com.example.commonutils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentsdb.client.bean.response.QueryResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;


import javax.xml.ws.ServiceMode;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Component
public class RedisService {

    private final static String HOST = "http://10.160.109.63";
    private final static int PORT = 4242;
    private final static String TAGK_OF_VIRTUALMACHINE = "fqdn";
    private final static List<String> METRICS_OF_VIRTUALMACHINE = Arrays.asList("cpu.usage.percent", "memory.used.percent", "interface.eth0.if_octets.rx", "interface.eth0.if_octets.tx", "disk.vda.disk_octets.write", "disk.vda.disk_octets.read");
    private final static List<String> METRICS_OF_SERVER = Arrays.asList("cpu.active.percent", "memory.used.percent", "interface.eth0.if_octets.rx", "interface.eth0.if_octets.tx", "disk.vda.disk_octets.write", "disk.vda.disk_octets.read");
    private final static List<String> METRICS_OF_POWER = Arrays.asList("pdu.power");
    private final static String TAGK = "fqdn";
    public boolean Data2Redis(String entity, Long startTimestamp, Long endTimestamp,
                              StringRedisTemplate stringRedisTemplate,ObjectMapper objectMapper)
            throws InterruptedException, ExecutionException, IOException {
        // 1. 对用户指定的起止时间进行切分，明确用户需要哪些段的数据
        List<String> timestampPairs = RecordUtil.segmentTimestamp(startTimestamp, endTimestamp);
        // 2. 根据时间段，构建Redis中应该对应的Key
        List<String> keys = timestampPairs.stream().map(timestampPair -> String.format("%s.%s", entity, timestampPair)).collect(Collectors.toList());
        // 3. 确定哪些Key在Redis中是不存在的
        HashOperations<String, Object, Object> opsForHash = stringRedisTemplate.opsForHash();
        List<String> keysNotInRedis = keys.stream().filter(key -> opsForHash.size(key) == 0).collect(Collectors.toList());
        // 4. 根据不存在的Key构建对应的标志位
        List<String> flagOfKeysNotInRedis = keysNotInRedis.stream().map(key -> String.format("%s.%s", key, "flag")).collect(Collectors.toList());
        // 5. 确定哪些时间段已有线程去取，哪些时间段需要自己去取
        ValueOperations<String, String> opsForValue = stringRedisTemplate.opsForValue();
        // 5.1 需要等待其他线程取的数据
        List<String> keysNeedToWait = flagOfKeysNotInRedis.stream().filter(key -> opsForValue.get(key) != null).collect(Collectors.toList());
        // 5.2 需要自己取的数据
        List<String> keysNeedToQuery = flagOfKeysNotInRedis.stream().filter(key -> opsForValue.get(key) == null).collect(Collectors.toList());
        // 6. 设置标志位，取数据，放入Redis中
        // 6.1 标志位
        keysNeedToQuery.stream().forEach(key -> opsForValue.setIfAbsent(key, "exist"));
        if (0 != keysNeedToQuery.size()) {
            // 6.2 取数据
            List<String> metricOfEntity = null;
            switch (entity) {
                case "openstack01":
                case "openstack02":
                    metricOfEntity = METRICS_OF_VIRTUALMACHINE;
                    break;
                case "compute01":
                    metricOfEntity = METRICS_OF_SERVER;
                    break;
                case "pdu-mini":
                    metricOfEntity = METRICS_OF_POWER;
                    break;
            }
            List<QueryResult> queryResultList = RecordUtil.queryRecordsOfMetricsInSpecificTimeSpan(HOST, PORT, Long.valueOf(keysNeedToQuery.get(0).split("\\.")[1]), Long.valueOf(keysNeedToQuery.get(keysNeedToQuery.size() - 1).split("\\.")[2]), metricOfEntity, TAGK, entity);
            // 6.3 放入Redis
            for (String keyNeedToQuery: keysNeedToQuery) {
                String key = String.format("%s.%s.%s", keyNeedToQuery.split("\\.")[0], keyNeedToQuery.split("\\.")[1], keyNeedToQuery.split("\\.")[2]);
                Map<String, LinkedHashMap<Long, Number>> map = RecordUtil.interceptQueryResultList2MapOfSpecificTimestampSegment(queryResultList, key);
                map.forEach((metric, linkedHashMap) -> {
                    try {
                        opsForHash.putIfAbsent(key, metric, objectMapper.writeValueAsString(linkedHashMap));
                    } catch (JsonProcessingException e) {
                        e.printStackTrace();
                    }
                });
            }
        }
        // 7. 确定其他线程已取到数据
        for (String keyNeedToWait: keysNeedToWait) {
            String key = String.format("%s.%s.%s", keyNeedToWait.split("\\.")[0], keyNeedToWait.split("\\.")[1], keyNeedToWait.split("\\.")[2]);
            while (0 == opsForHash.size(key));
        }
        return true;
    }
}
