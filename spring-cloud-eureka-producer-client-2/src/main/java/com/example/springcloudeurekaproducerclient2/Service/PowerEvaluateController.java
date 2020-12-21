package com.example.springcloudeurekaproducerclient2.Service;

import com.example.commonutils.RedisService;
import com.example.gRPC.GRPCPowerEvaluateClient;
import com.example.gRPC.GRPCPowerPredictClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

@RestController
@Slf4j
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

    @PostMapping("/powerevaluate")
    public String powerPredict(@RequestParam(value = "host", required = true) String host,
                               @RequestParam(value = "targetTimestamp", required = true) long targetTimestamp,
                               @RequestParam(value = "algorithm", required = true) String algorithm) {
        //Redis 交互逻辑 待补充
        GRPCPowerEvaluateClient client = new GRPCPowerEvaluateClient(lidata_ip, 50051);
        String power = client.evaluate("compute01",String.valueOf(targetTimestamp),String.valueOf(algorithm));
        return power+environment.getProperty("local.server.port");
    }
}
