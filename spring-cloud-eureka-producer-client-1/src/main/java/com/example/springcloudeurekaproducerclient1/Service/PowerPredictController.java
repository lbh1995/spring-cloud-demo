package com.example.springcloudeurekaproducerclient1.Service;

import com.example.commonutils.RedisService;
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
public class PowerPredictController {
    private final DiscoveryClient dc;
    final static String lidata_ip = "10.160.109.63";
    final static String lbh_win_ip = "192.168.0.84";
    final static String localhost_ip = "127.0.0.1";
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

    @PostMapping("/powerpredict")
    public String powerPredict(@RequestParam(value = "host", required = true) String host,
                              @RequestParam(value = "targetTimestamp", required = true) long targetTimestamp,
                              @RequestParam(value = "algorithm", required = true) String algorithm) {
        long start = targetTimestamp - 10;
        long end = targetTimestamp -1;
        RedisService rs = new RedisService();
        try {
            rs.Data2Redis(host,start,end,stringRedisTemplate,objectMapper);
            rs.Data2Redis("pdu-mini",start,end,stringRedisTemplate,objectMapper);
        } catch (InterruptedException | ExecutionException | IOException e) {
            e.printStackTrace();
        }
        GRPCPowerPredictClient client = new GRPCPowerPredictClient(lidata_ip, 50051);
        String power = client.greet("compute01",String.valueOf(start),String.valueOf(end));
        return power+environment.getProperty("local.server.port");
    }
}
