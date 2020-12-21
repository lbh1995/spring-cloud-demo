package com.example.commonutils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentsdb.client.OpenTSDBClient;
import org.opentsdb.client.OpenTSDBClientFactory;
import org.opentsdb.client.OpenTSDBConfig;
import org.opentsdb.client.bean.request.Query;
import org.opentsdb.client.bean.request.SubQuery;
import org.opentsdb.client.bean.response.QueryResult;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;

// 工具类
// 该类封装了读取、处理数据的诸多方法
public class RecordUtil {

    /**
     * 查询某物理机某指标在指定时间段内的记录
     * @param host OpenTSDB运行的主机域名
     * @param port OpenTSDB运行的端口号
     * @param startTimestamp 开始时间戳
     * @param endTimestamp 结束时间戳
     * @param metric 指标名
     * @param tag 筛选条件的标签
     * @param server 以主机名进行筛选
     * @return 若查询结果为空，返回只包含指标名的空数据；否则返回实际查询到的数据
     * @throws IOException 异常
     * @throws InterruptedException 异常
     * @throws ExecutionException 异常
     */
    @Deprecated
    public static QueryResult queryServerRecordsInSpecificTimeSpan(String host, int port, long startTimestamp, long endTimestamp, String metric, String tag, String server) throws IOException, InterruptedException, ExecutionException {
        OpenTSDBConfig openTSDBConfig = OpenTSDBConfig.address(host, port).config();
        OpenTSDBClient openTSDBClient = OpenTSDBClientFactory.connect(openTSDBConfig);
        Query query = Query
                .begin(startTimestamp)
                .end(endTimestamp)
                .sub(SubQuery
                        .aggregator(SubQuery.Aggregator.SUM)
                        .metric(metric)
                        .tag(tag, server)
                        .build())
                .build();
        List<QueryResult> resultList = openTSDBClient.query(query);
        openTSDBClient.gracefulClose();
        if (0 == resultList.size()) {
            QueryResult emptyQueryResult = new QueryResult();
            emptyQueryResult.setMetric(metric);
            return emptyQueryResult;
        } else return resultList.get(0);
    }

    /**
     * 查询某物理机（或虚拟机）某个（或多个）指标在指定时间段内的记录
     * @param host OpenTSDB运行的主机域名
     * @param port OpenTSDB运行的端口号
     * @param startTimestamp 开始时间戳
     * @param endTimestamp 结束时间戳
     * @param metrics 指标名列表
     * @param tagk 筛选条件的键
     * @param tagv 筛选条件的值
     * @return List[QueryResult(metric, tags, aggregateTags, dps), ...]
     * @throws IOException 异常
     * @throws InterruptedException 异常
     * @throws ExecutionException 异常
     */
    public static List<QueryResult> queryRecordsOfMetricsInSpecificTimeSpan(String host, int port, long startTimestamp, long endTimestamp, List<String> metrics, String tagk, String tagv) throws IOException, InterruptedException, ExecutionException {
        OpenTSDBConfig openTSDBConfig = OpenTSDBConfig.address(host, port).config();
        OpenTSDBClient openTSDBClient = OpenTSDBClientFactory.connect(openTSDBConfig);
        List<SubQuery> subQueryList = new ArrayList<>();
        metrics.stream().forEach(metric -> {
            SubQuery subQuery = SubQuery
                    .aggregator(SubQuery.Aggregator.SUM)
                    .metric(metric)
                    .tag(tagk, tagv)
                    .build();
            subQueryList.add(subQuery);
        });
        Query query = Query
                .begin(startTimestamp)
                .end(endTimestamp)
                .sub(subQueryList)
                .build();
        List<QueryResult> queryResultList = openTSDBClient.query(query);
        openTSDBClient.gracefulClose();
        return queryResultList;
    }

    /**
     * 查询某物理机（或虚拟机）某个（或多个）指标在指定时间戳的记录
     * @param host OpenTSDB运行的主机域名
     * @param port OpenTSDB运行的端口号
     * @param targetTimestamp 目标时间戳
     * @param metrics 指标名列表
     * @param tagk 筛选条件的键
     * @param tagv 筛选条件的值
     * @return List[QueryResult(metric, tags, aggregateTags, dps), ...]
     * @throws IOException 异常
     * @throws InterruptedException 异常
     * @throws ExecutionException 异常
     */
    public static List<QueryResult> queryRecordOfMetricsAtSpecificTimestamp(String host, int port, long targetTimestamp, List<String> metrics, String tagk, String tagv) throws IOException, InterruptedException, ExecutionException {
        OpenTSDBConfig openTSDBConfig = OpenTSDBConfig.address(host, port).config();
        OpenTSDBClient openTSDBClient = OpenTSDBClientFactory.connect(openTSDBConfig);
        List<SubQuery> subQueryList = new ArrayList<>();
        metrics.stream().forEach(metric -> {
            SubQuery subQuery = SubQuery
                    .aggregator(SubQuery.Aggregator.SUM)
                    .metric(metric)
                    .tag(tagk, tagv)
                    .build();
            subQueryList.add(subQuery);
        });
        Query query = Query
                .begin(targetTimestamp)
                .end(targetTimestamp + 1)
                .sub(subQueryList)
                .build();
        List<QueryResult> queryResultList = openTSDBClient.query(query);
        openTSDBClient.gracefulClose();
        for (QueryResult queryResult: queryResultList) {
            queryResult.getDps().remove(targetTimestamp + 1);
        }
        return queryResultList;
    }

    /**
     * 切分时间戳，从而固定Redis中数据的时间间隔
     * 例如segmentFactor为100，用户请求的数据为[startTimestamp, endTimestamp] = [1568294220, 1568295330]
     * 经过切分后，Redis中将存储数据：[1568294000, 1568294999]和[1568295000, 1568295999]
     * @param startTimestamp 开始时间戳
     * @param endTimestamp 结束时间戳
     * @return List["startTimestamp_1.endTimestamp_1", "startTimestamp_2.endTimestamp_2", ...]
     */
    public static List<String> segmentTimestamp(Long startTimestamp, Long endTimestamp, int segmentFactor) {
        long startTimestampSegment = startTimestamp / segmentFactor;
        long numOfSegments = endTimestamp / segmentFactor - startTimestampSegment + 1;
        List<String> timestampPairs = new ArrayList<>();
        for (long i = 0; i < numOfSegments; ++ i) {
            timestampPairs.add(String.format("%s.%s", (startTimestampSegment + i) * segmentFactor, (startTimestampSegment + i) * segmentFactor + segmentFactor - 1));
        }
        return timestampPairs;
    }

    /**
     * 根据Key：entity.startTimestamp.endTimestamp从查询结果中截取出在时间段[startTimestamp，endTimestamp]内的指标值，并封装成Map<metric, <timestamp, value>>返回。
     * @param queryResultList
     * @param key entity.startTimestamp.endTimestamp
     * @return Map<metric, Map<timestamp, value>>
     */
    public static Map<String, LinkedHashMap<Long, Number>> interceptQueryResultList2MapOfSpecificTimestampSegment(List<QueryResult> queryResultList, String key) {
        Map<String, LinkedHashMap<Long, Number>> map = new HashMap<>();
        for (QueryResult queryResult: queryResultList) {
            LinkedHashMap<Long, Number> linkedHashMap = new LinkedHashMap<>();
            queryResult.getDps().forEach((timestamp, value) -> {
                if (Long.valueOf(key.split("\\.")[1]) <= timestamp && timestamp <= Long.valueOf(key.split("\\.")[2])) {
                    linkedHashMap.put(timestamp, value);
                }
            });
            map.put(queryResult.getMetric(), linkedHashMap);
        }
        return map;
    }


    public static Map<String,Set<String>> queryVMsOnHostInSpecificTimeSpan(String host, int port, long startTimestamp, long endTimestamp, String targetServert) {
        Map<String,Set<String>> map = new HashMap<>();
        for (int i=0;i<100;i++){
            double d = Math.random();
            int l = (int)(d*3);
            Set<String> set = new HashSet<>();
            for (int j=0;j<l;j++){
                set.add("openstack0"+j);
            }
            map.put(String.valueOf(startTimestamp+i),set);
        }
        return map;
    }
}
