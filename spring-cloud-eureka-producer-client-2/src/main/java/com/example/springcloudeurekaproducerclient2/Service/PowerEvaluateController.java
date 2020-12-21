package com.example.springcloudeurekaproducerclient2.Service;

import com.example.commonutils.RedisService;
import com.example.gRPC.GRPCPowerEvaluateClient;
import com.example.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.configurationprocessor.json.JSONException;
import org.springframework.boot.configurationprocessor.json.JSONObject;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;

@RestController
@Slf4j
@CrossOrigin(origins = "*",maxAge = 3600)
public class PowerEvaluateController {
    private final DiscoveryClient dc;
    final static String lidata_ip = "10.160.109.63";
    final static String lbh_win_ip = "192.168.0.84";
    final static String localhost_ip = "127.0.0.1";
    @Autowired
    public PowerEvaluateController(DiscoveryClient discoveryClient){
        this.dc = discoveryClient;
    }
    @Autowired
    Environment environment;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @RequestMapping(value = "/powerevaluate/{targetTimestamp}/{algorithm}/{hostname}",
            method = RequestMethod.GET, produces = "application/json;charset=UTF-8")
    public ResponseJsonTemplate powerPredict(@PathVariable("hostname") String host,
                                             @PathVariable("targetTimestamp") String targetTimestamp_,
                                             @PathVariable("algorithm") String algorithm) throws UnknownHostException {
        long targetTimestamp = Long.parseLong(targetTimestamp_);
        RedisService rs = new RedisService();
        ResponsePowerEvaluateJson rjt = new ResponsePowerEvaluateJson();
        rjt.setTimeStamp(targetTimestamp);
        rjt.setServer(InetAddress.getLocalHost().getHostAddress()+"："+environment.getProperty("local.server.port"));
        rjt.setHostname(host);
        boolean flag = false;
        DataNotExistFlag dataNotExist = new DataNotExistFlag(false);
        try {
            while (!flag){
                //flag = rs.multipleHostData2Redis(host, start, end, objectMapper,stringRedisTemplate);
                flag = rs.saveDataOfTargetServerWithVirtualMachinesInSpecifiedTimePeriod(host, targetTimestamp, targetTimestamp, dataNotExist,
                        stringRedisTemplate, objectMapper);
                if (dataNotExist.getFlag()){
                    rjt.setStatus("500");
                    rjt.setVmlist(null);
                    rjt.setDescription("数据缺失");
                    return rjt;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        GRPCPowerEvaluateClient client = new GRPCPowerEvaluateClient(lidata_ip, 50052);
        String evaluatePower = client.evaluate(host,String.valueOf(targetTimestamp_),String.valueOf(algorithm));
        JSONObject jsonObject = null;
        try {
            jsonObject = new JSONObject(evaluatePower);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        Iterator keys = jsonObject.keys();
        List<VMData> vmDataList = new ArrayList<>();
        while (keys.hasNext()){
            String key = (String) keys.next();
            try {
                if (key.equals(host)){
                    rjt.setRealPower(jsonObject.getInt(key));
                }else{
                    VMData vmData = new VMData();
                    vmData.setVmName(key);
                    vmData.setEvaluatePower(jsonObject.getInt(key));
                    vmDataList.add(vmData);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        rjt.setVmlist(vmDataList);
        rjt.setStatus("200");
        return rjt;
    }
}
