package com.example.springcloudeurekaproducerclient1.Service;

import com.example.commonutils.RedisService;
import com.example.gRPC.GRPCPowerPredictClient;
import com.example.model.PowerData;
import com.example.model.ResponseJsonObject;
import com.example.model.ResponseJsonTemplate;
import com.example.model.ResponsePowerJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@RestController
@Slf4j
@CrossOrigin(origins = "*",maxAge = 3600)
public class PowerPredictController {
    private final DiscoveryClient dc;
    final static String lidata_ip = "10.160.109.63";
    final static String lbh_win_ip = "192.168.0.84";
    final static String localhost_ip = "127.0.0.1";
    final static Map<String,Long> HIS_DATA_LEN = new HashMap<>();
    static {
        HIS_DATA_LEN.put("ARIMA",10l);
        HIS_DATA_LEN.put("RF",10l);
    }
    @Autowired
    public PowerPredictController(DiscoveryClient discoveryClient){
        this.dc = discoveryClient;
    }
    @Autowired
    Environment environment;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @RequestMapping(value = "/powerpredict/{targetTimestamp}/{algorithm}/{hostname}",
            method = RequestMethod.GET, produces = "application/json;charset=UTF-8")
    public ResponseJsonTemplate powerPredict(@PathVariable("hostname") String host,
                                             @PathVariable("targetTimestamp") String targetTimestamp_,
                                             @PathVariable("algorithm") String algorithm) {
        System.out.println(System.currentTimeMillis());
        long his_data_len = HIS_DATA_LEN.get(algorithm);
        long targetTimestamp = Long.valueOf(targetTimestamp_);
        long start = targetTimestamp - his_data_len;
        long end = targetTimestamp -1;
        RedisService rs = new RedisService();
        boolean flag = false;
        try {
            flag = rs.multipleData2Redis(host, start, end, objectMapper);
        } catch (Exception e) {
            e.printStackTrace();
        }
        long t1 = System.currentTimeMillis();
        GRPCPowerPredictClient client = new GRPCPowerPredictClient(lidata_ip, 50051);
        String power = client.greet(host,String.valueOf(start),String.valueOf(end),algorithm);
        System.out.println("grpc与算法耗时"+(System.currentTimeMillis()-t1));
        ResponsePowerJson rjt = new ResponsePowerJson();
        if (power!=null && !power.equals("")){
            String[] response_power = power.split(",");
            PowerData pd = new PowerData();
            pd.setReal(Integer.valueOf(response_power[0]));
            pd.setPred(Float.valueOf(response_power[1]).intValue());
            pd.setTimestamp(targetTimestamp_);
            rjt.setStatus("200");
            rjt.setData(pd);
        }else{
            rjt.setStatus("500");
            rjt.setData(null);
        }
        rjt.setServer(environment.getProperty("local.server.port"));
        System.out.println(System.currentTimeMillis());
        return rjt;
        /*
        ResponseJsonObject rjo = new ResponseJsonObject();
        if (power!=null) {
            rjo.setStatus("200");
            rjo.setServer(environment.getProperty("local.server.port"));
            List<String> data = new ArrayList<>();
            data.add(power);
            rjo.setData(data);
        }
        return rjo;
         */
    }
}