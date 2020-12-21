package com.example.commonutils;

import com.example.model.DataNotExistFlag;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentsdb.client.bean.response.QueryResult;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;


import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Component
public class RedisService {

    private final static String[] VIRTUAL_MACHINE_SET = new String[]{"openstack01", "openstack02"};
    // 虚拟机配置信息：「2」代表「2核2G」
    private final static String[] VIRTUAL_MACHINE_DESCRIPTION = new String[]{"2", "2"};
    private final static String HOST = "http://10.160.109.63";
    private final static int PORT = 4242;
    private final static List<String> METRICS_OF_SERVER = Arrays.asList("cpu.active.percent", "memory.used.percent", "interface.eth0.if_octets.rx", "interface.eth0.if_octets.tx", "disk.sda.disk_octets.write", "disk.sda.disk_octets.read");
    private final static List<String> METRICS_OF_VIRTUAL_MACHINE = Arrays.asList("cpu.usage.percent", "memory.used.percent", "interface.eth0.if_octets.rx", "interface.eth0.if_octets.tx", "disk.vda.disk_octets.write", "disk.vda.disk_octets.read");
    private final static List<String> METRICS_OF_POWER = Arrays.asList("pdu.power");
    private final static String TAG_K = "fqdn";
    private final static int TIME_LEN = 100;
    private static final Set<String> SET_OF_VIRTUAL_MACHINE = new HashSet<>(Arrays.asList("openstack01", "openstack02"));

    /**
     * 根据指定起止时间戳将目标服务器的各项指标和能耗数据存入Redis中。
     * @param targetServer 目标服务器
     * @param startTimestamp 开始时间戳
     * @param endTimestamp 终止时间戳
     * @param dataNotExist 课题一返回的数据是否不存在
     * @return 成功返回"success"，失败返回"failure"。
     */
    public boolean saveDataOfTargetServerInSpecifiedTimePeriod(String targetServer, Long startTimestamp,
                                                               Long endTimestamp, DataNotExistFlag dataNotExist,
                                                               StringRedisTemplate stringRedisTemplate,
                                                               ObjectMapper objectMapper) {
        long enterTimestamp = Instant.now().toEpochMilli();
        System.out.println("enter method: " + enterTimestamp);
        long timeForQuery = 0L;

        ValueOperations<String, String> opsForValue = stringRedisTemplate.opsForValue();
        HashOperations<String, Object, Object> opsForHash = stringRedisTemplate.opsForHash();

        // 根据指定起止时间戳，切分成段。每段长度设置为100。
        List<String> timestampPairs = RecordUtil.segmentTimestamp(startTimestamp, endTimestamp, TIME_LEN);

        // 构建flag：targetServer.startTimestamp.endTimestamp.flag。
        // 查询Redis中是否存在这些flag。
        // 不存在的flag放入flagsNeedQueried中自己去取，存在的则放入flagsNeedWaited中等待其他线程放入Redis。
        List<String> flags = timestampPairs.stream().map(timestampPair -> String.format("%s.%s.flag", targetServer, timestampPair)).collect(Collectors.toList());
        List<String> multiGetResult = opsForValue.multiGet(flags); // 「和Redis交互一次」
        if (null == multiGetResult) return false;
        List<String> flagsNeedQueried = new ArrayList<>();
        List<String> flagsNeedWaited = new ArrayList<>();
        for (int i = 0; i < flags.size(); ++ i) {
            String result = multiGetResult.get(i);
            String flag = flags.get(i);
            // 值为空或值可以更新，则加入flagsNeedQueried。
            if (null == result) flagsNeedQueried.add(flag);
            else if ("update".equals(result)) {
                stringRedisTemplate.delete(flag);
                flagsNeedQueried.add(flag);
            }
            else flagsNeedWaited.add(flag);
        }

        // 根据flagsNeedQueried查询数据并放入Redis。
        //long currentTimestamp = Instant.now().getEpochSecond();
        long currentTimestamp = 1568560469;
        for (String flagNeedQueried: flagsNeedQueried) {
            // 设置flag成功则取数据，不成功说明已有线程去取，则加入flagsNeedWaited。
            Boolean setSuccess = opsForValue.setIfAbsent(flagNeedQueried, "exist"); // 「和Redis交互一次」
            String[] split = flagNeedQueried.split("\\.");
            long startTimestampOfSegment = Long.parseLong(split[1]);
            long endTimestampOfSegment = Long.parseLong(split[2]);
            if (setSuccess || currentTimestamp<endTimestampOfSegment) {
                try {
                    long beginTimestampOfQuery = Instant.now().toEpochMilli();
                    List<QueryResult> records = new ArrayList<>();
                    List<QueryResult> recordsOfMetrics = RecordUtil.queryRecordsOfMetricsInSpecificTimeSpan(HOST, PORT, startTimestampOfSegment, endTimestampOfSegment, METRICS_OF_SERVER, TAG_K, targetServer); // 「和OpenTSDB交互两次」
                    if (0 == recordsOfMetrics.size()) {
                        stringRedisTemplate.delete(flagNeedQueried);
                        dataNotExist.setFlag(true);
                        return false;
                    } else if (currentTimestamp < endTimestampOfSegment) opsForValue.set(flagNeedQueried, "update");
                    records.addAll(recordsOfMetrics);
                    List<QueryResult> recordsOfPower = RecordUtil.queryRecordsOfMetricsInSpecificTimeSpan(HOST, PORT, startTimestampOfSegment, endTimestampOfSegment, METRICS_OF_POWER, TAG_K, "pdu-mini");
                    if (0 == recordsOfPower.size()) {
                        stringRedisTemplate.delete(flagNeedQueried);
                        dataNotExist.setFlag(true);
                        return false;
                    } else if (currentTimestamp < endTimestampOfSegment) opsForValue.set(flagNeedQueried, "update");
                    records.addAll(recordsOfPower);
                    long endTimestampForQuery = Instant.now().toEpochMilli();
                    timeForQuery += (endTimestampForQuery - beginTimestampOfQuery);

                    // 构建key：targetServer.startTimestamp.endTimestamp
                    String key = flagNeedQueried.replace(".flag", "");
                    Map<String, String> fieldAndValueMap = new HashMap<>();

                    // 检查是否有空缺时间戳，若有则补上并设置其值为null。
                    for (QueryResult queryResult: records) {
                        LinkedHashMap<Long, Number> dps = queryResult.getDps();
                        if (100 == dps.size()) fieldAndValueMap.put(queryResult.getMetric(), objectMapper.writeValueAsString(dps));
                        else if (0 == dps.size()) {
                            stringRedisTemplate.delete(flagNeedQueried);
                            dataNotExist.setFlag(true);
                            //两种解决方案
                            return false;
                        } else {
                            LinkedHashMap<Long, Number> dpsAfterChecked = new LinkedHashMap<>();
                            for (long i = startTimestampOfSegment; i <= endTimestampOfSegment; ++ i) dpsAfterChecked.put(i, dps.getOrDefault(i, null));
                            fieldAndValueMap.put(queryResult.getMetric(), objectMapper.writeValueAsString(dpsAfterChecked));
                        }
                    }
                    opsForHash.putAll(key, fieldAndValueMap); // 「和Redis交互一次」
                } catch (IOException | InterruptedException | ExecutionException e) {
                    // 若发生异常，则flag置为"update"。
                    e.printStackTrace();
                    stringRedisTemplate.delete(flagNeedQueried);
                    return false;
                }
            } else flagsNeedWaited.add(flagNeedQueried);
        }

        // 根据flagsNeedWaited确认数据已放入Redis。
        long startTimestampForWaiting = Instant.now().getEpochSecond();
        for (String flagNeedWaited: flagsNeedWaited) {
            String key = flagNeedWaited.replace(".flag", "");
            while (0 == opsForHash.size(key)) {
                long timeForWaiting = Instant.now().getEpochSecond() - startTimestampForWaiting;
                if (5 < timeForWaiting) {
                    opsForValue.set(flagNeedWaited, "update");
                    return false;
                }
            }
        }

        long exitTimestamp = Instant.now().toEpochMilli();
        System.out.println("exit method: " + exitTimestamp);
        System.out.println("total time cost: " + (exitTimestamp - enterTimestamp));
        System.out.println("time for query: " + timeForQuery);
        return true;
    }

    public boolean saveDataOfTargetVirtualMachineInSpecifiedTimePeriod(String targetVirtualMachine, Long startTimestamp,
                                                                       Long endTimestamp, DataNotExistFlag dataNotExist,
                                                                       StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        long enterTimestamp = Instant.now().toEpochMilli();
        long timeForQuery = 0L;

        ValueOperations<String, String> opsForValue = stringRedisTemplate.opsForValue();
        HashOperations<String, Object, Object> opsForHash = stringRedisTemplate.opsForHash();

        // 根据指定起止时间戳，切分成段。每段长度设置为100。
        List<String> timestampPairs = RecordUtil.segmentTimestamp(startTimestamp, endTimestamp, TIME_LEN);

        // 构建flag：targetVirtualMachine.startTimestamp.endTimestamp.flag。
        // 查询Redis中是否存在这些flag。
        // 不存在的flag放入flagsNeedQueried中自己去取，存在的则放入flagsNeedWaited中等待其他线程放入Redis。
        List<String> flags = timestampPairs.stream().map(timestampPair -> String.format("%s.%s.flag", targetVirtualMachine, timestampPair)).collect(Collectors.toList());
        List<String> multiGetResult = opsForValue.multiGet(flags); // 「和Redis交互一次」
        if (null == multiGetResult) return false;
        List<String> flagsNeedQueried = new ArrayList<>();
        List<String> flagsNeedWaited = new ArrayList<>();
        for (int i = 0; i < flags.size(); ++ i) {
            String result = multiGetResult.get(i);
            String flag = flags.get(i);
            // 值为空或值可以更新，则加入flagsNeedQueried。
            if (null == result) flagsNeedQueried.add(flag);
            else if ("update".equals(result)) {
                stringRedisTemplate.delete(flag);
                flagsNeedQueried.add(flag);
            }
            else flagsNeedWaited.add(flag);
        }

        // 根据flagsNeedQueried查询数据并放入Redis。
        long currentTimestamp = Instant.now().getEpochSecond();
        for (String flagNeedQueried: flagsNeedQueried) {
            // 设置flag成功则取数据，不成功说明已有线程去取，则加入flagsNeedWaited。
            Boolean setSuccess = opsForValue.setIfAbsent(flagNeedQueried, "exist"); // 「和Redis交互一次」
            String[] split = flagNeedQueried.split("\\.");
            long startTimestampOfSegment = Long.parseLong(split[1]);
            long endTimestampOfSegment = Long.parseLong(split[2]);
            if (setSuccess || currentTimestamp < endTimestampOfSegment) {
                try {
                    long beginTimestampOfQuery = Instant.now().toEpochMilli();
                    List<QueryResult> recordsOfMetrics = RecordUtil.queryRecordsOfMetricsInSpecificTimeSpan(HOST, PORT, startTimestampOfSegment, endTimestampOfSegment, METRICS_OF_VIRTUAL_MACHINE, TAG_K, targetVirtualMachine); // 「和OpenTSDB交互一次」
                    if (0 == recordsOfMetrics.size()) {
                        stringRedisTemplate.delete(flagNeedQueried);
                        dataNotExist.setFlag(true);
                        return false;
                    } else if (currentTimestamp < endTimestampOfSegment) opsForValue.set(flagNeedQueried, "update");
                    long endTimestampForQuery = Instant.now().toEpochMilli();
                    timeForQuery += (endTimestampForQuery - beginTimestampOfQuery);

                    // 构建key：targetServer.startTimestamp.endTimestamp
                    String key = flagNeedQueried.replace(".flag", "");
                    Map<String, String> fieldAndValueMap = new HashMap<>();

                    // 检查是否有空缺时间戳，若有则补上并设置其值为null。
                    for (QueryResult queryResult: recordsOfMetrics) {
                        LinkedHashMap<Long, Number> dps = queryResult.getDps();
                        if (100 == dps.size()) fieldAndValueMap.put(queryResult.getMetric(), objectMapper.writeValueAsString(dps));
                        else if (0 == dps.size()) {
                            stringRedisTemplate.delete(flagNeedQueried);
                            dataNotExist.setFlag(true);
                            return false;
                        } else {
                            LinkedHashMap<Long, Number> dpsAfterChecked = new LinkedHashMap<>();
                            for (long i = startTimestampOfSegment; i <= endTimestampOfSegment; ++ i) dpsAfterChecked.put(i, dps.getOrDefault(i, null));
                            fieldAndValueMap.put(queryResult.getMetric(), objectMapper.writeValueAsString(dpsAfterChecked));
                        }
                    }

                    opsForHash.putAll(key, fieldAndValueMap); // 「和Redis交互一次」
                } catch (IOException | InterruptedException | ExecutionException e) {
                    // 若发生异常，则flag置为"update"。
                    e.printStackTrace();
                    stringRedisTemplate.delete(flagNeedQueried);
                    return false;
                }
            } else flagsNeedWaited.add(flagNeedQueried);
        }

        // 根据flagsNeedWaited确认数据已放入Redis。
        long startTimestampForWaiting = Instant.now().getEpochSecond();
        for (String flagNeedWaited: flagsNeedWaited) {
            String key = flagNeedWaited.replace(".flag", "");
            while (0 == opsForHash.size(key)) {
                long timeForWaiting = Instant.now().getEpochSecond() - startTimestampForWaiting;
                if (5 < timeForWaiting) {
                    opsForValue.set(flagNeedWaited, "update");
                    return false;
                }
            }
        }

        long exitTimestamp = Instant.now().toEpochMilli();
        System.out.println("saveDataOfTargetVirtualMachineInSpecifiedTimePeriod");
        System.out.println("total time cost: " + (exitTimestamp - enterTimestamp));
        System.out.println("time for query: " + timeForQuery);

        return true;
    }

    public boolean saveDataOfTargetServerWithVirtualMachinesInSpecifiedTimePeriod_prototype(String targetServer, Long startTimestamp,
                                                                                            Long endTimestamp, DataNotExistFlag dataNotExist,
                                                                                            StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        long enterTimestamp = Instant.now().toEpochMilli();

        HashOperations<String, Object, Object> opsForHash = stringRedisTemplate.opsForHash();

        Set<String> setOfAppearedVirtualMachine = new HashSet<>();
        // 获取目标物理机上每个时刻的虚拟机名，并放入Redis中。
        // key: targetServer.virtualMachineSet
        String keyOfVirtualMachineSetOnTargetServer = String.format("%s.virtualMachineSet", targetServer);
        Map<String, String> mapOfTimestamp2VirtualMachineSet = new LinkedHashMap<>();
        // 模拟物理机上每个时刻的虚拟机名。
        for (long timestamp = startTimestamp; timestamp <= endTimestamp; ++ timestamp) {
            Set<String> setOfVirtualMachineAtTimestamp = new HashSet<>();
            try {
                mapOfTimestamp2VirtualMachineSet.put(String.valueOf(timestamp), objectMapper.writeValueAsString(SET_OF_VIRTUAL_MACHINE));
            } catch (JsonProcessingException e) {
                e.printStackTrace();
                return false;
            }
            setOfAppearedVirtualMachine.addAll(setOfVirtualMachineAtTimestamp);
        }
        opsForHash.putAll(keyOfVirtualMachineSetOnTargetServer, mapOfTimestamp2VirtualMachineSet);

        // 获取物理机数据并放入Redis中。
        while (!saveDataOfTargetServerInSpecifiedTimePeriod(targetServer, startTimestamp,
                endTimestamp, dataNotExist, stringRedisTemplate, objectMapper)) {
            if (dataNotExist.getFlag()) {
                return false;
            }
        }

        // 获取虚拟机数据并放入Redis中。
        for (String virtualMachine: SET_OF_VIRTUAL_MACHINE) {
            while (!saveDataOfTargetVirtualMachineInSpecifiedTimePeriod(virtualMachine, startTimestamp,
                    endTimestamp, dataNotExist, stringRedisTemplate, objectMapper)) {
                if (dataNotExist.getFlag()) {
                    return false;
                }
            }
        }

        long exitTimestamp = Instant.now().toEpochMilli();
        System.out.println("saveDataOfTargetServerWithVirtualMachinesInSpecifiedTimePeriod_prototype");
        System.out.println("total time cost: " + (exitTimestamp - enterTimestamp));

        return true;
    }

    public boolean saveDataOfTargetServerWithVirtualMachinesInSpecifiedTimePeriod(String targetServer, Long startTimestamp,
                                                                                  Long endTimestamp, DataNotExistFlag dataNotExist,
                                                                                  StringRedisTemplate stringRedisTemplate,ObjectMapper objectMapper) {
        long enterTimestamp = Instant.now().toEpochMilli();

        ValueOperations<String, String> opsForValue = stringRedisTemplate.opsForValue();
        HashOperations<String, Object, Object> opsForHash = stringRedisTemplate.opsForHash();

        // 根据指定起止时间戳，切分成段。每段长度设置为100。
        List<String> timestampPairs = RecordUtil.segmentTimestamp(startTimestamp, endTimestamp,TIME_LEN);

        // 构建flag：targetServer.startTimestamp.endTimestamp.virtualMachineSet.flag。
        // 查询Redis中是否存在这些flag。
        // 不存在的flag放入flagsNeedQueried中自己去取，存在的则放入flagsNeedWaited中等待其他线程放入Redis。
        List<String> flags = timestampPairs.stream().map(timestampPair -> String.format("%s.%s.virtualMachineSet.flag", targetServer, timestampPair)).collect(Collectors.toList());
        List<String> multiGetResult = opsForValue.multiGet(flags); // 「和Redis交互一次」
        if (null == multiGetResult) return false;
        List<String> flagsNeedQueried = new ArrayList<>();
        List<String> flagsNeedWaited = new ArrayList<>();
        for (int i = 0; i < flags.size(); ++ i) {
            String result = multiGetResult.get(i);
            String flag = flags.get(i);
            // 值为空或值可以更新，则加入flagsNeedQueried。
            if (null == result) flagsNeedQueried.add(flag);
            else if ("update".equals(result)) {
                stringRedisTemplate.delete(flag);
                flagsNeedQueried.add(flag);
            } else flagsNeedWaited.add(flag);
        }

        // 根据flagsNeedQueried查询数据并放入Redis。
        long currentTimestamp = Instant.now().getEpochSecond();
        for (String flagNeedQueried: flagsNeedQueried) {
            // 设置flag成功则取数据，不成功说明已有线程去取，则加入flagsNeedWaited。
            Boolean setSuccess = opsForValue.setIfAbsent(flagNeedQueried, "exist"); // 「和Redis交互一次」
            String[] split = flagNeedQueried.split("\\.");
            long startTimestampOfSegment = Long.parseLong(split[1]);
            long endTimestampOfSegment = Long.parseLong(split[2]);
            if (setSuccess || currentTimestamp < endTimestampOfSegment) {
                String keyOfTargetServerStartTimestampEndTimestampVirtualMachineSet = flagNeedQueried.replace(".flag", "");
                Map<String, String> valueOfTargetServerStartTimestampEndTimestampVirtualMachineSet = new LinkedHashMap<>();
                for (long timestamp = startTimestampOfSegment; timestamp <= (Math.min(currentTimestamp, endTimestampOfSegment)); ++ timestamp) {
                    try {
                        valueOfTargetServerStartTimestampEndTimestampVirtualMachineSet.put(String.valueOf(timestamp), objectMapper.writeValueAsString(SET_OF_VIRTUAL_MACHINE));
                    } catch (JsonProcessingException e) {
                        e.printStackTrace();
                        stringRedisTemplate.delete(flagNeedQueried);
                        return false;
                    }
                }

                while (!saveDataOfTargetServerInSpecifiedTimePeriod(targetServer, startTimestampOfSegment,
                        endTimestampOfSegment, dataNotExist,stringRedisTemplate,objectMapper)) {
                    if (dataNotExist.getFlag()) {
                        stringRedisTemplate.delete(flagNeedQueried);
                        return false;
                    }
                }
                for (String virtualMachine: SET_OF_VIRTUAL_MACHINE) {
                    while (!saveDataOfTargetVirtualMachineInSpecifiedTimePeriod(virtualMachine, startTimestampOfSegment,
                            endTimestampOfSegment, dataNotExist,stringRedisTemplate,objectMapper)) {
                        if (dataNotExist.getFlag()) {
                            stringRedisTemplate.delete(flagNeedQueried);
                            return false;
                        }
                    }
                }
                opsForHash.putAll(keyOfTargetServerStartTimestampEndTimestampVirtualMachineSet, valueOfTargetServerStartTimestampEndTimestampVirtualMachineSet);
                if (currentTimestamp < endTimestampOfSegment) opsForValue.set(flagNeedQueried, "update");
            } else flagsNeedWaited.add(flagNeedQueried);
        }

        // 根据flagsNeedWaited确认数据已放入Redis。
        long startTimestampForWaiting = Instant.now().getEpochSecond();
        for (String flagNeedWaited: flagsNeedWaited) {
            String key = flagNeedWaited.replace(".flag", "");
            while (0 == opsForHash.size(key)) {
                long timeForWaiting = Instant.now().getEpochSecond() - startTimestampForWaiting;
                if (5 < timeForWaiting) {
                    opsForValue.set(flagNeedWaited, "update");
                    return false;
                }
            }
        }

        long exitTimestamp = Instant.now().toEpochMilli();
        System.out.println("saveDataOfTargetServerWithVirtualMachinesInSpecifiedTimePeriod");
        System.out.println("total time cost: " + (exitTimestamp - enterTimestamp));

        return true;
    }

    /**
     * 该方法根据指定的目标服务器与目标时间戳，向课题一请求目标时间戳时目标服务器上的虚拟机列表、目标服务器各项指标数据以及各虚拟机各项指标数据，将这些数据放入Redis中。
     * 该方法考虑了「并发」问题。
     * 该方法在发生异常时会将flag键都置为"error"，并返回"failure"给调用端。
     * 注：该方法调用了RecordUtil类的queryRecordOfMetricsAtSpecificTimestamp方法。
     * @param targetServer 目标服务器
     * @param targetTimestamp 目标时间戳
     * @return 成功返回 true ，失败则返回 false
     */
    public boolean singleData2Redis(String targetServer, Long targetTimestamp, StringRedisTemplate stringRedisTemplate) {
        ValueOperations<String, String> opsForValue = stringRedisTemplate.opsForValue();
        SetOperations<String, String> opsForSet = stringRedisTemplate.opsForSet();
        HashOperations<String, Object, Object> opsForHash = stringRedisTemplate.opsForHash();

        // 该列表记录当前已写入值的flag，当发生异常时，对该列表内的所有flag赋值"error"。
        List<String> currentFlagList = new ArrayList<>();

        // 1. 检查Redis中是否存在键「targetServer.targetTimestamp.virtualMachineSet.flag」，如果不存在则设置键并向课题一请求数据，如果存在则等待数据放入Redis执行其他逻辑。
        // 1.1. 构建键flagOfVirtualMachineSetOnTargetServerAtTargetTimestamp = targetServer.targetTimestamp.virtualMachineSet.flag
        String flagOfVirtualMachineSetOnTargetServerAtTargetTimestamp = String.format("%s.%s.virtualMachineSet.flag", targetServer, targetTimestamp);
        currentFlagList.add(flagOfVirtualMachineSetOnTargetServerAtTargetTimestamp);
        // 1.2. 构建键keyOfVirtualMachineSetOnTargetServerAtTargetTimestamp = targetServer.targetTimestamp.virtualMachineSet
        //      构建键keyOfVirtualMachineDescriptionOnTargetServerAtTargetTimestamp添加虚拟机配置信息
        String keyOfVirtualMachineSetOnTargetServerAtTargetTimestamp = String.format("%s.%s.virtualMachineSet", targetServer, targetTimestamp);
        String keyOfVirtualMachineDescriptionOnTargetServerAtTargetTimestamp = String.format("%s.%s.virtualMachineDescription", targetServer, targetTimestamp);
        if ("exist".equals(opsForValue.get(flagOfVirtualMachineSetOnTargetServerAtTargetTimestamp))) {
            // 1.3. 键「targetServer.targetTimestamp.virtualMachineSet.flag」存在，说明已有线程去取数据，等待，直至数据放入Redis。
            // 注意：可能出现在某一时间戳该服务器上没有虚拟机的特殊情况，这里暂且不考虑这种情况。
            while (0 == opsForSet.size(keyOfVirtualMachineSetOnTargetServerAtTargetTimestamp));
        } else {
            // 1.4. 键「targetServer.targetTimestamp.virtualMachineSet.flag」不存在，说明没有线程去取数据，设置该键并向课题一请求数据放入Redis。
            opsForValue.set(flagOfVirtualMachineSetOnTargetServerAtTargetTimestamp, "exist");
            // 注意：由于还没能和课题一对接，目前采用的是我们模拟的环境，在服务器compute01上只有两个虚拟机openstack01和openstack02，因此这里放入的值写死为{openstack01，openstack02}。
            opsForSet.add(keyOfVirtualMachineSetOnTargetServerAtTargetTimestamp, VIRTUAL_MACHINE_SET);
            // 添加虚拟机配置信息
            Map<String, String> virtualMachineDescriptionMap = new HashMap<>();
            for (int i = 0; i < VIRTUAL_MACHINE_SET.length; ++ i) virtualMachineDescriptionMap.put(VIRTUAL_MACHINE_SET[i], VIRTUAL_MACHINE_DESCRIPTION[i]);
            opsForHash.putAll(keyOfVirtualMachineDescriptionOnTargetServerAtTargetTimestamp, virtualMachineDescriptionMap);
        }

        // 2. 检查Redis中是否存在键「targetServer.targetTimestamp.flag」，如果不存在则设置键并向课题一请求数据，如果存在则等待数据放入Redis执行其他逻辑。
        // 2.1. 构建键flagOfTargetServerAtTargetTimestamp = targetServer.targetTimestamp.flag
        String flagOfTargetServerAtTargetTimestamp = String.format("%s.%s.flag", targetServer, targetTimestamp);
        currentFlagList.add(flagOfTargetServerAtTargetTimestamp);
        // 2.2. 构建键keyOfTargetServerAtTargetTimestamp = targetServer.targetTimestamp
        String keyOfTargetServerAtTargetTimestamp = String.format("%s.%s", targetServer, targetTimestamp);
        if ("exist".equals(opsForValue.get(flagOfTargetServerAtTargetTimestamp))) {
            // 2.3. 键「targetServer.targetTimestamp.flag」存在，说明已有线程去取数据，等待，直至数据放入Redis。
            while (0 == opsForHash.size(keyOfTargetServerAtTargetTimestamp));
        } else {
            // 2.4. 键「targetServer.targetTimestamp.flag」不存在，说明没有线程去取数据，设置该键并向课题一请求数据放入Redis。
            opsForValue.set(flagOfTargetServerAtTargetTimestamp, "exist");
            // 注意：由于还没能和课题一对接，目前采用的是我们模拟的环境，数据放在服务器lidata上的OpenTSDB上，因此这里写死请求数据的逻辑。
            try {
                List<QueryResult> dataOfTargetServerAtTargetTimestamp = RecordUtil.queryRecordOfMetricsAtSpecificTimestamp(HOST, PORT, targetTimestamp, METRICS_OF_SERVER, TAG_K, targetServer);
                Map<String, String> mapOfDataOfTargetServerAtTargetTimestamp = new HashMap<>();
                dataOfTargetServerAtTargetTimestamp.forEach(queryResult -> mapOfDataOfTargetServerAtTargetTimestamp.put(queryResult.getMetric(), queryResult.getDps().get(targetTimestamp).toString()));
                opsForHash.putAll(keyOfTargetServerAtTargetTimestamp, mapOfDataOfTargetServerAtTargetTimestamp);
            } catch (NullPointerException | IOException | InterruptedException | ExecutionException e) {
                // 2.5. 一旦出现异常，则将currentFlagList内的flag值都设为"error"，这样其他线程会去重新取数据，同时返回失败提示。
                currentFlagList.forEach(flag -> opsForValue.set(flag, "error"));
                opsForSet.remove(keyOfVirtualMachineSetOnTargetServerAtTargetTimestamp, VIRTUAL_MACHINE_SET);
                e.printStackTrace();
                return false;
            }
        }

        // 3. 读取该时间戳该服务器上的虚拟机列表，并将对应的数据放入Redis中。
        // 3.1 读取该时间戳该服务器上的虚拟机列表。
        Set<String> virtualMachineSet = opsForSet.members(keyOfVirtualMachineSetOnTargetServerAtTargetTimestamp);
        // 3.2 根据列表，查询Redis中是否存在数据。
        // 注意：可能出现在某一时间戳该服务器上没有虚拟机的特殊情况，这里暂且不考虑这种情况。
        for (String virtualMachine: virtualMachineSet) {
            // 3.3. 构建键flagOfVirtualMachineAtTargetTimestamp = virtualMachine.targetTimestamp.flag
            String flagOfVirtualMachineAtTargetTimestamp = String.format("%s.%s.flag", virtualMachine, targetTimestamp);
            currentFlagList.add(flagOfVirtualMachineAtTargetTimestamp);
            // 3.4. 构建键keyOfVirtualMachineAtTargetTimestamp = virtualMachine.targetTimestamp
            String keyOfVirtualMachineAtTargetTimestamp = String.format("%s.%s", virtualMachine, targetTimestamp);
            if ("exist".equals(opsForValue.get(flagOfVirtualMachineAtTargetTimestamp))) {
                // 3.5. 键「virtualMachine.targetTimestamp.flag」存在，说明已有线程去取数据，等待，直至数据放入Redis。
                while (0 == opsForHash.size(keyOfVirtualMachineAtTargetTimestamp));
            } else {
                // 3.6. 键「virtualMachine.targetTimestamp.flag」不存在，说明没有线程去取数据，设置该键并向课题一请求数据放入Redis。
                opsForValue.set(flagOfVirtualMachineAtTargetTimestamp, "exist");
                // 注意：由于还没能和课题一对接，目前采用的是我们模拟的环境，数据放在服务器lidata上的OpenTSDB上，因此这里写死请求数据的逻辑。
                try {
                    List<QueryResult> dataOfVirtualMachineAtTargetTimestamp = RecordUtil.queryRecordOfMetricsAtSpecificTimestamp(HOST, PORT, targetTimestamp, METRICS_OF_VIRTUAL_MACHINE, TAG_K, virtualMachine);
                    Map<String, String> mapOfDataOfVirtualMachineAtTargetTimestamp = new HashMap<>();
                    dataOfVirtualMachineAtTargetTimestamp.forEach(queryResult -> mapOfDataOfVirtualMachineAtTargetTimestamp.put(queryResult.getMetric(), queryResult.getDps().get(targetTimestamp).toString()));
                    opsForHash.putAll(keyOfVirtualMachineAtTargetTimestamp, mapOfDataOfVirtualMachineAtTargetTimestamp);
                } catch (NullPointerException | IOException | InterruptedException | ExecutionException e) {
                    // 3.7. 一旦出现异常，则将currentFlagList内的flag值都设为"error"，这样其他线程会去重新取数据，同时返回失败提示。
                    currentFlagList.forEach(flag -> opsForValue.set(flag, "error"));
                    opsForSet.remove(keyOfVirtualMachineSetOnTargetServerAtTargetTimestamp, VIRTUAL_MACHINE_SET);
                    e.printStackTrace();
                    return false;
                }
            }
        }

        return true;
    }

    /*public boolean singleData2Redis(String targetServer, Long startTimestamp, Long endTimestamp, ObjectMapper objectMapper) {
        List<String> timestampPairs = RecordUtil.segmentTimestamp(startTimestamp, endTimestamp, TIME_LEN);

        // 1. 检查Redis中是否存在键「targetServer.startTimestamp.endTimestamp.virtualMachineSet.flag」，如果不存在则设置键并向课题一请求数据，如果存在则等待数据放入Redis执行其他逻辑。
        // 1.1. 构建键flagOfVirtualMachineSetOnTargetServer = targetServer.startTimestamp.endTimestamp.virtualMachineSet.flag
        List<String> flagsOfVirtualMachineSetOnTargetServer = timestampPairs.stream().map(timestampPair -> String.format("%s.%s.virtualMachineSet.flag", targetServer, timestampPair)).collect(Collectors.toList());
        Jedis jedis = JedisPoolUtils.getJedis();
        //List<String> needConfirmed = flagsOfVirtualMachineSetOnTargetServer.stream().filter(flag -> "exist".equals(jedis.get(flag))).map(flag -> flag.replaceAll(".flag", "")).collect(Collectors.toList());
        List<String> needQueried = flagsOfVirtualMachineSetOnTargetServer.stream().filter(flag -> !"exist".equals(jedis.get(flag))).map(flag -> flag.replaceAll(".flag", "")).collect(Collectors.toList());
        // 2.3. 获取数据并放入Redis
        // 该列表记录当前已写入值的flag，当发生异常时，对该列表内的所有flag赋值"error"。
        List<String> currentFlagList = new ArrayList<>();
        Set<String> vms = new HashSet<>();
        for (String key : needQueried){
            long lock = jedis.setnx(String.format("%s.flag", key), "exist");
            if (lock==1) {
                try {
                    currentFlagList.add(key);
                    Map<String,Set<String>> queryResultMap;
                    queryResultMap = RecordUtil.queryVMsOnHostInSpecificTimeSpan(HOST, PORT, Long.parseLong(key.split("\\.")[1]), Long.parseLong(key.split("\\.")[2]), targetServer);
                    Map<String,String> map2Redis = new HashMap<>();
                    for (String curkey : queryResultMap.keySet()) {
                        Set<String> curSet = queryResultMap.get(curkey);
                        for (String vm : curSet) {
                            vms.add(vm);
                        }
                        map2Redis.put(curkey,objectMapper.writeValueAsString(curSet));
                    }
                    jedis.hmset(key,map2Redis);
                } catch (Exception e) {
                    currentFlagList.forEach(currentFlag -> jedis.set(String.format("%s.flag", currentFlag), "error"));
                    e.printStackTrace();
                    return false;
                }
            }
        }
        if ("exist".equals(opsForValue.get(flagOfVirtualMachineSetOnTargetServerAtTargetTimestamp))) {
            // 1.3. 键「targetServer.targetTimestamp.virtualMachineSet.flag」存在，说明已有线程去取数据，等待，直至数据放入Redis。
            // 注意：可能出现在某一时间戳该服务器上没有虚拟机的特殊情况，这里暂且不考虑这种情况。
            while (0 == opsForSet.size(keyOfVirtualMachineSetOnTargetServerAtTargetTimestamp));
        } else {
            // 1.4. 键「targetServer.targetTimestamp.virtualMachineSet.flag」不存在，说明没有线程去取数据，设置该键并向课题一请求数据放入Redis。
            opsForValue.set(flagOfVirtualMachineSetOnTargetServerAtTargetTimestamp, "exist");
            // 注意：由于还没能和课题一对接，目前采用的是我们模拟的环境，在服务器compute01上只有两个虚拟机openstack01和openstack02，因此这里放入的值写死为{openstack01，openstack02}。
            opsForSet.add(keyOfVirtualMachineSetOnTargetServerAtTargetTimestamp, VIRTUAL_MACHINE_SET);
            // 添加虚拟机配置信息
            Map<String, String> virtualMachineDescriptionMap = new HashMap<>();
            for (int i = 0; i < VIRTUAL_MACHINE_SET.length; ++ i) virtualMachineDescriptionMap.put(VIRTUAL_MACHINE_SET[i], VIRTUAL_MACHINE_DESCRIPTION[i]);
            opsForHash.putAll(keyOfVirtualMachineDescriptionOnTargetServerAtTargetTimestamp, virtualMachineDescriptionMap);
        }

        // 2. 检查Redis中是否存在键「targetServer.targetTimestamp.flag」，如果不存在则设置键并向课题一请求数据，如果存在则等待数据放入Redis执行其他逻辑。
        // 2.1. 构建键flagOfTargetServerAtTargetTimestamp = targetServer.targetTimestamp.flag
        String flagOfTargetServerAtTargetTimestamp = String.format("%s.%s.flag", targetServer, targetTimestamp);
        currentFlagList.add(flagOfTargetServerAtTargetTimestamp);
        // 2.2. 构建键keyOfTargetServerAtTargetTimestamp = targetServer.targetTimestamp
        String keyOfTargetServerAtTargetTimestamp = String.format("%s.%s", targetServer, targetTimestamp);
        if ("exist".equals(opsForValue.get(flagOfTargetServerAtTargetTimestamp))) {
            // 2.3. 键「targetServer.targetTimestamp.flag」存在，说明已有线程去取数据，等待，直至数据放入Redis。
            while (0 == opsForHash.size(keyOfTargetServerAtTargetTimestamp));
        } else {
            // 2.4. 键「targetServer.targetTimestamp.flag」不存在，说明没有线程去取数据，设置该键并向课题一请求数据放入Redis。
            opsForValue.set(flagOfTargetServerAtTargetTimestamp, "exist");
            // 注意：由于还没能和课题一对接，目前采用的是我们模拟的环境，数据放在服务器lidata上的OpenTSDB上，因此这里写死请求数据的逻辑。
            try {
                List<QueryResult> dataOfTargetServerAtTargetTimestamp = RecordUtil.queryRecordOfMetricsAtSpecificTimestamp(HOST, PORT, targetTimestamp, METRICS_OF_SERVER, TAG_K, targetServer);
                Map<String, String> mapOfDataOfTargetServerAtTargetTimestamp = new HashMap<>();
                dataOfTargetServerAtTargetTimestamp.forEach(queryResult -> mapOfDataOfTargetServerAtTargetTimestamp.put(queryResult.getMetric(), queryResult.getDps().get(targetTimestamp).toString()));
                opsForHash.putAll(keyOfTargetServerAtTargetTimestamp, mapOfDataOfTargetServerAtTargetTimestamp);
            } catch (NullPointerException | IOException | InterruptedException | ExecutionException e) {
                // 2.5. 一旦出现异常，则将currentFlagList内的flag值都设为"error"，这样其他线程会去重新取数据，同时返回失败提示。
                currentFlagList.forEach(flag -> opsForValue.set(flag, "error"));
                opsForSet.remove(keyOfVirtualMachineSetOnTargetServerAtTargetTimestamp, VIRTUAL_MACHINE_SET);
                e.printStackTrace();
                return false;
            }
        }

        // 3. 读取该时间戳该服务器上的虚拟机列表，并将对应的数据放入Redis中。
        // 3.1 读取该时间戳该服务器上的虚拟机列表。
        Set<String> virtualMachineSet = opsForSet.members(keyOfVirtualMachineSetOnTargetServerAtTargetTimestamp);
        // 3.2 根据列表，查询Redis中是否存在数据。
        // 注意：可能出现在某一时间戳该服务器上没有虚拟机的特殊情况，这里暂且不考虑这种情况。
        for (String virtualMachine: virtualMachineSet) {
            // 3.3. 构建键flagOfVirtualMachineAtTargetTimestamp = virtualMachine.targetTimestamp.flag
            String flagOfVirtualMachineAtTargetTimestamp = String.format("%s.%s.flag", virtualMachine, targetTimestamp);
            currentFlagList.add(flagOfVirtualMachineAtTargetTimestamp);
            // 3.4. 构建键keyOfVirtualMachineAtTargetTimestamp = virtualMachine.targetTimestamp
            String keyOfVirtualMachineAtTargetTimestamp = String.format("%s.%s", virtualMachine, targetTimestamp);
            if ("exist".equals(opsForValue.get(flagOfVirtualMachineAtTargetTimestamp))) {
                // 3.5. 键「virtualMachine.targetTimestamp.flag」存在，说明已有线程去取数据，等待，直至数据放入Redis。
                while (0 == opsForHash.size(keyOfVirtualMachineAtTargetTimestamp));
            } else {
                // 3.6. 键「virtualMachine.targetTimestamp.flag」不存在，说明没有线程去取数据，设置该键并向课题一请求数据放入Redis。
                opsForValue.set(flagOfVirtualMachineAtTargetTimestamp, "exist");
                // 注意：由于还没能和课题一对接，目前采用的是我们模拟的环境，数据放在服务器lidata上的OpenTSDB上，因此这里写死请求数据的逻辑。
                try {
                    List<QueryResult> dataOfVirtualMachineAtTargetTimestamp = RecordUtil.queryRecordOfMetricsAtSpecificTimestamp(HOST, PORT, targetTimestamp, METRICS_OF_VIRTUAL_MACHINE, TAG_K, virtualMachine);
                    Map<String, String> mapOfDataOfVirtualMachineAtTargetTimestamp = new HashMap<>();
                    dataOfVirtualMachineAtTargetTimestamp.forEach(queryResult -> mapOfDataOfVirtualMachineAtTargetTimestamp.put(queryResult.getMetric(), queryResult.getDps().get(targetTimestamp).toString()));
                    opsForHash.putAll(keyOfVirtualMachineAtTargetTimestamp, mapOfDataOfVirtualMachineAtTargetTimestamp);
                } catch (NullPointerException | IOException | InterruptedException | ExecutionException e) {
                    // 3.7. 一旦出现异常，则将currentFlagList内的flag值都设为"error"，这样其他线程会去重新取数据，同时返回失败提示。
                    currentFlagList.forEach(flag -> opsForValue.set(flag, "error"));
                    opsForSet.remove(keyOfVirtualMachineSetOnTargetServerAtTargetTimestamp, VIRTUAL_MACHINE_SET);
                    e.printStackTrace();
                    return false;
                }
            }
        }

        return true;
    }*/

    /**
     * 该方法根据指定的目标服务器与目标时间段，向课题一请求目标时间段内目标服务器上的各项指标数据与能耗数据，将这些数据放入Redis中。
     * 该方法考虑了「并发」问题。
     * 该方法考虑了「endTimestamp大于currentTimestamp」的情况，在该情况下，targetServer.metric/power.startTimestamp.endTimestamp是会被后来的线程更新的。
     * 该方法在发生异常时会将flag键都置为"error"，并返回"failure"给调用端。
     * 注：该方法调用了RecordUtil类的queryRecordsOfMetricsInSpecificTimeSpan方法。
     * @param targetServer 目标服务器
     * @param startTimestamp 目标时间段
     * @param endTimestamp
     * @return 成功返回 true，失败则返回 false
     */
    public boolean multipleHostData2Redis(String targetServer, Long startTimestamp, Long endTimestamp, StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        System.out.println(System.currentTimeMillis());
        ValueOperations<String, String> opsForValue = stringRedisTemplate.opsForValue();
        HashOperations<String, Object, Object> opsForHash = stringRedisTemplate.opsForHash();

        // 1. 根据指定的起止时间戳确定数据的分段。
        //    记录该方法被调用时的时间戳、分段的最大时间戳。
        List<String> timestampPairs = RecordUtil.segmentTimestamp(startTimestamp, endTimestamp, TIME_LEN);

        // 2.1. 根据分段构建服务器flag列表
        //      flag: targetServer.metric.startTimestamp.endTimestamp.flag
        List<String> flagOfTargetServerMetricData = timestampPairs.stream().map(timestampPair -> String.format("%s.metric.%s.flag", targetServer, timestampPair)).collect(Collectors.toList());
        // 2.2. 查询哪些数据已有线程去取，哪些数据需要自己去取，同时构建key列表
        //      key: targetServer.metric.startTimestamp.endTimestamp
        Jedis jedis = JedisPoolUtils.getJedis();
        long t2 = System.currentTimeMillis();
        List<String> list = flagOfTargetServerMetricData.stream().filter(flag -> "exist".equals(jedis.get(flag))).map(flag -> flag.replaceAll(".flag", "")).collect(Collectors.toList());
        System.out.println("redis取数据耗时1:"+(System.currentTimeMillis()-t2));
        long t8 = System.currentTimeMillis();
        List<String> needConfirmed = flagOfTargetServerMetricData.stream().filter(flag -> "exist".equals(opsForValue.get(flag))).map(flag -> flag.replaceAll(".flag", "")).collect(Collectors.toList());
        System.out.println("对比实验："+(System.currentTimeMillis()-t8));
        List<String> needQueried = flagOfTargetServerMetricData.stream().filter(flag -> !"exist".equals(opsForValue.get(flag))).map(flag -> flag.replaceAll(".flag", "")).collect(Collectors.toList());
        // 2.3. 获取数据并放入Redis
        List<String> currentKeys = new ArrayList<>();
        for (int i = 0; i < needQueried.size(); ++ i) {
            String key = needQueried.get(i);
            currentKeys.add(key);
            List<QueryResult> queryResultList = null;
            opsForValue.set(String.format("%s.flag", key), "exist");
            try {
                long t3 = System.currentTimeMillis();
                queryResultList = RecordUtil.queryRecordsOfMetricsInSpecificTimeSpan(HOST, PORT, Long.parseLong(key.split("\\.")[2]), Long.parseLong(key.split("\\.")[3]), METRICS_OF_SERVER, TAG_K, "compute01");
                System.out.println("外部数据取数据耗时1:"+(System.currentTimeMillis()-t3));
                if (TIME_LEN != queryResultList.get(0).getDps().size()) opsForValue.set(String.format("%s.flag", key), "update");
                Map<String,String> temp = new HashMap<>();
                long t4 = System.currentTimeMillis();
                queryResultList.forEach(queryResult -> {
                    try {
                        temp.put(queryResult.getMetric(),objectMapper.writeValueAsString(queryResult.getDps()));
                        //opsForHash.put(key, queryResult.getMetric(), objectMapper.writeValueAsString(queryResult.getDps()));
                    } catch (JsonProcessingException e) {
                        e.printStackTrace();
                    }
                });
                opsForHash.putAll(key,temp);
                System.out.println("redis存数据耗时:"+(System.currentTimeMillis()-t4));
            } catch (IOException | InterruptedException | ExecutionException e) {
                currentKeys.forEach(currentKey -> opsForValue.set(String.format("%s.flag", currentKey), "error"));
                e.printStackTrace();
                return false;
            }
        }
        // 2.4. 确认其他线程已取到数据
        for (String keyToBeConfirmed: needConfirmed) {
            while (0 == opsForHash.size(keyToBeConfirmed));
        }

        // 3.1. 根据分段构建能耗flag列表
        //      flag: targetServer.power.startTimestamp.endTimestamp.flag
        List<String> flagOfTargetServerPowerData = timestampPairs.stream().map(timestampPair -> String.format("%s.power.%s.flag", targetServer, timestampPair)).collect(Collectors.toList());
        // 3.2. 查询哪些数据已有线程去取，哪些数据需要自己去取，同时构建key列表
        //      key: targetServer.power.startTimestamp.endTimestamp
        long t5 = System.currentTimeMillis();
        List<String> needConfirmedOfPower = flagOfTargetServerPowerData.stream().filter(flag -> "exist".equals(opsForValue.get(flag))).map(flag -> flag.replaceAll(".flag", "")).collect(Collectors.toList());
        System.out.println("redis取数据耗时2:"+(System.currentTimeMillis()-t5));
        List<String> needQueriedOfPower = flagOfTargetServerPowerData.stream().filter(flag -> !"exist".equals(opsForValue.get(flag))).map(flag -> flag.replaceAll(".flag", "")).collect(Collectors.toList());
        // 3.3. 获取数据并放入Redis
        List<String> currentKeysOfPower = new ArrayList<>();
        for (int i = 0; i < needQueriedOfPower.size(); ++ i) {
            String key = needQueriedOfPower.get(i);
            currentKeysOfPower.add(key);
            List<QueryResult> queryResultList = null;
            opsForValue.set(String.format("%s.flag", key), "exist");
            try {
                long t6 = System.currentTimeMillis();
                queryResultList = RecordUtil.queryRecordsOfMetricsInSpecificTimeSpan(HOST, PORT, Long.parseLong(key.split("\\.")[2]), Long.parseLong(key.split("\\.")[3]), METRICS_OF_POWER, TAG_K, "pdu-mini");
                System.out.println("外部数据取数据耗时2:"+(System.currentTimeMillis()-t6));
                if (TIME_LEN != queryResultList.get(0).getDps().size()) opsForValue.set(String.format("%s.flag", key), "update");
                long t7 = System.currentTimeMillis();
                queryResultList.forEach(queryResult -> {
                    try {
                        opsForHash.put(key, queryResult.getMetric(), objectMapper.writeValueAsString(queryResult.getDps()));
                    } catch (JsonProcessingException e) {
                        e.printStackTrace();
                    }
                });
                System.out.println("redis存数据耗时2:"+(System.currentTimeMillis()-t7));
            } catch (IOException | InterruptedException | ExecutionException e) {
                currentKeysOfPower.forEach(currentKey -> opsForValue.set(String.format("%s.flag", currentKey), "error"));
                e.printStackTrace();
                return false;
            }
        }
        // 3.4. 确认其他线程已取到数据
        for (String keyToBeConfirmed: needConfirmedOfPower) {
            while (0 == opsForHash.size(keyToBeConfirmed));
        }
        System.out.println(System.currentTimeMillis());
        return true;
    }

    public boolean multipleHostData2Redis(String targetServer, Long startTimestamp, Long endTimestamp, ObjectMapper objectMapper, StringRedisTemplate stringRedisTemplate) {
        System.out.println(System.currentTimeMillis());
        // 1. 根据指定的起止时间戳确定数据的分段。
        //    记录该方法被调用时的时间戳、分段的最大时间戳。
        List<String> timestampPairs = RecordUtil.segmentTimestamp(startTimestamp, endTimestamp, TIME_LEN);
        // 2.1. 根据分段构建服务器flag列表
        //      flag: targetServer.metric.startTimestamp.endTimestamp.flag
        List<String> flagOfTargetServerMetricData = timestampPairs.stream().map(timestampPair -> String.format("%s.metric.%s.flag", targetServer, timestampPair)).collect(Collectors.toList());
        // 2.2. 查询哪些数据已有线程去取，哪些数据需要自己去取，同时构建key列表
        //      key: targetServer.metric.startTimestamp.endTimestamp
        Jedis jedis = JedisPoolUtils.getJedis();
        //List<String> needConfirmed = flagOfTargetServerMetricData.stream().filter(flag -> "exist".equals(jedis.get(flag))).map(flag -> flag.replaceAll(".flag", "")).collect(Collectors.toList());
        List<String> needQueried = flagOfTargetServerMetricData.stream().filter(flag -> !"exist".equals(jedis.get(flag))).map(flag -> flag.replaceAll(".flag", "")).collect(Collectors.toList());
        // 2.3. 获取数据并放入Redis
        List<String> currentKeys = new ArrayList<>();
        for (int i = 0; i < needQueried.size(); ++ i) {
            String key = needQueried.get(i);
            List<QueryResult> queryResultList = null;
            long lock = jedis.setnx(String.format("%s.flag", key), "exist");
            if (lock==1) {
                try {
                    currentKeys.add(key);
                    long t3 = System.currentTimeMillis();
                    queryResultList = RecordUtil.queryRecordsOfMetricsInSpecificTimeSpan(HOST, PORT, Long.parseLong(key.split("\\.")[2]), Long.parseLong(key.split("\\.")[3]), METRICS_OF_SERVER, TAG_K, targetServer);
                    System.out.println("外部数据取数据耗时1:" + (System.currentTimeMillis() - t3));
                    Map<String, String> temp = new HashMap<>();
                    queryResultList.forEach(queryResult -> {
                        try {
                            temp.put(queryResult.getMetric(), objectMapper.writeValueAsString(queryResult.getDps()));
                            //opsForHash.put(key, queryResult.getMetric(), objectMapper.writeValueAsString(queryResult.getDps()));
                        } catch (JsonProcessingException e) {
                            e.printStackTrace();
                        }
                    });
                    long t4 = System.currentTimeMillis();
                    jedis.hmset(key, temp);
                    System.out.println("redis存数据耗时:" + (System.currentTimeMillis() - t4));
                    long t14 = System.currentTimeMillis();
                    stringRedisTemplate.opsForHash().putAll(key, temp);
                    System.out.println("redis存数据耗时(对比实验):" + (System.currentTimeMillis() - t14));
                    long t9 = System.currentTimeMillis();
                    if (TIME_LEN != queryResultList.get(0).getDps().size()) {
                        jedis.del(String.format("%s.flag", key));
                    }
                    System.out.println("redis删数据耗时:" + (System.currentTimeMillis() - t9));
                    currentKeys.remove(currentKeys.size()-1);
                    System.out.println("redis存数据耗时:" + (System.currentTimeMillis() - t4));
                } catch (IOException | InterruptedException | ExecutionException e) {
                    currentKeys.forEach(currentKey -> jedis.del(String.format("%s.flag", currentKey)));
                    e.printStackTrace();
                    jedis.close();
                    return false;
                }
            }
        }

        // 2.4. 确认其他线程已取到数据
        //for (String keyToBeConfirmed: needConfirmed) {
        //    while (0 == opsForHash.size(keyToBeConfirmed));
        //}

        // 3.1. 根据分段构建能耗flag列表
        //      flag: targetServer.power.startTimestamp.endTimestamp.flag
        List<String> flagOfTargetServerPowerData = timestampPairs.stream().map(timestampPair -> String.format("%s.power.%s.flag", targetServer, timestampPair)).collect(Collectors.toList());
        // 3.2. 查询哪些数据已有线程去取，哪些数据需要自己去取，同时构建key列表
        //      key: targetServer.power.startTimestamp.endTimestamp
        long t5 = System.currentTimeMillis();
        //List<String> needConfirmedOfPower = flagOfTargetServerPowerData.stream().filter(flag -> "exist".equals(jedis.get(flag))).map(flag -> flag.replaceAll(".flag", "")).collect(Collectors.toList());
        List<String> needQueriedOfPower = flagOfTargetServerPowerData.stream().filter(flag -> !"exist".equals(jedis.get(flag))).map(flag -> flag.replaceAll(".flag", "")).collect(Collectors.toList());
        System.out.println("redis取数据耗时2:"+(System.currentTimeMillis()-t5));
        // 3.3. 获取数据并放入Redis
        List<String> currentKeysOfPower = new ArrayList<>();
        for (int i = 0; i < needQueriedOfPower.size(); ++ i) {
            String key = needQueriedOfPower.get(i);
            List<QueryResult> queryResultList = null;
            long lock = jedis.setnx(String.format("%s.flag", key), "exist");
            if(lock==1) {
                try {
                    currentKeysOfPower.add(key);
                    long t6 = System.currentTimeMillis();
                    queryResultList = RecordUtil.queryRecordsOfMetricsInSpecificTimeSpan(HOST, PORT, Long.parseLong(key.split("\\.")[2]), Long.parseLong(key.split("\\.")[3]), METRICS_OF_POWER, TAG_K, "pdu-mini");
                    System.out.println("外部数据取数据耗时2:" + (System.currentTimeMillis() - t6));
                    long t7 = System.currentTimeMillis();
                    Map<String, String> temp = new HashMap<>();
                    queryResultList.forEach(queryResult -> {
                        try {
                            temp.put(queryResult.getMetric(), objectMapper.writeValueAsString(queryResult.getDps()));
                            //opsForHash.put(key, queryResult.getMetric(), objectMapper.writeValueAsString(queryResult.getDps()));
                        } catch (JsonProcessingException e) {
                            e.printStackTrace();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                    jedis.hmset(key,temp);
                    if (TIME_LEN != queryResultList.get(0).getDps().size()) {
                        jedis.del(String.format("%s.flag", key));
                    }
                    //已经存好的，就不需要再做一次读取
                    currentKeysOfPower.remove(currentKeysOfPower.size()-1);
                    System.out.println("redis存数据耗时2:" + (System.currentTimeMillis() - t7));
                } catch (IOException | InterruptedException | ExecutionException e) {
                    currentKeysOfPower.forEach(currentKeyOfPower -> jedis.set(String.format("%s.flag", currentKeyOfPower), "error"));
                    e.printStackTrace();
                    jedis.close();
                    return false;
                }
            }
        }
        // 3.4. 确认其他线程已取到数据
        //for (String keyToBeConfirmed: needConfirmedOfPower) {
        //    while (0 == opsForHash.size(keyToBeConfirmed));
        //}
        jedis.close();
        return true;
    }

    public boolean multipleVMData2Redis(String targetServer, Long startTimestamp, Long endTimestamp, ObjectMapper objectMapper) {
        System.out.println(System.currentTimeMillis());

        // 1. 根据指定的起止时间戳确定数据的分段。
        //    记录该方法被调用时的时间戳、分段的最大时间戳。
        List<String> timestampPairs = RecordUtil.segmentTimestamp(startTimestamp, endTimestamp, TIME_LEN);

        // 2.1. 根据分段构建服务器flag列表
        //      flag: targetServer.metric.startTimestamp.endTimestamp.flag
        List<String> flagOfTargetServerMetricData = timestampPairs.stream().map(timestampPair -> String.format("%s.metric.%s.flag", targetServer, timestampPair)).collect(Collectors.toList());
        // 2.2. 查询哪些数据已有线程去取，哪些数据需要自己去取，同时构建key列表
        //      key: targetServer.metric.startTimestamp.endTimestamp
        Jedis jedis = JedisPoolUtils.getJedis();
        //List<String> needConfirmed = flagOfTargetServerMetricData.stream().filter(flag -> "exist".equals(jedis.get(flag))).map(flag -> flag.replaceAll(".flag", "")).collect(Collectors.toList());
        List<String> needQueried = flagOfTargetServerMetricData.stream().filter(flag -> !"exist".equals(jedis.get(flag))).map(flag -> flag.replaceAll(".flag", "")).collect(Collectors.toList());
        // 2.3. 获取数据并放入Redis
        List<String> currentKeys = new ArrayList<>();
        for (int i = 0; i < needQueried.size(); ++ i) {
            String key = needQueried.get(i);
            List<QueryResult> queryResultList = null;
            long lock = jedis.setnx(String.format("%s.flag", key), "exist");
            if (lock==1) {
                try {
                    currentKeys.add(key);
                    long t3 = System.currentTimeMillis();
                    queryResultList = RecordUtil.queryRecordsOfMetricsInSpecificTimeSpan(HOST, PORT, Long.parseLong(key.split("\\.")[2]), Long.parseLong(key.split("\\.")[3]), METRICS_OF_VIRTUAL_MACHINE, TAG_K, targetServer);
                    System.out.println("外部数据取数据耗时1:" + (System.currentTimeMillis() - t3));
                    if (TIME_LEN != queryResultList.get(0).getDps().size()) {
                        jedis.set(String.format("%s.flag", key), "update");
                    }
                    Map<String, String> temp = new HashMap<>();
                    long t4 = System.currentTimeMillis();
                    queryResultList.forEach(queryResult -> {
                        try {
                            temp.put(queryResult.getMetric(), objectMapper.writeValueAsString(queryResult.getDps()));
                            //opsForHash.put(key, queryResult.getMetric(), objectMapper.writeValueAsString(queryResult.getDps()));
                        } catch (JsonProcessingException e) {
                            e.printStackTrace();
                        }
                    });
                    jedis.hmset(key, temp);
                    System.out.println("redis存数据耗时:" + (System.currentTimeMillis() - t4));
                } catch (IOException | InterruptedException | ExecutionException e) {
                    currentKeys.forEach(currentKey -> jedis.set(String.format("%s.flag", currentKey), "error"));
                    e.printStackTrace();
                    return false;
                }
            }
        }
        return true;
    }
}
