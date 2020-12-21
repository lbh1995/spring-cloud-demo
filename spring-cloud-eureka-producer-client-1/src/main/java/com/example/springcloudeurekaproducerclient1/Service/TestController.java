package com.example.springcloudeurekaproducerclient1.Service;


import com.example.gRPC.GRPCPowerPredictClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;

@RestController
@Slf4j
public class TestController {
    final static String lidata_ip = "10.160.109.63";
    final static String lbh_win_ip = "192.168.0.84";
    final static String localhost_ip = "127.0.0.1";
    @Autowired
    Environment environment;
    @GetMapping("/grpc_test")
    public String test(){
        System.out.println(environment.getProperty("local.server.port"));
        GRPCPowerPredictClient client = new GRPCPowerPredictClient(lbh_win_ip, 50051);
        String res = client.greet("lbhmbp","1","100","1");
        return res + " - " + environment.getProperty("local.server.port");
    }
    @GetMapping("/hello/producer")
    public String helloProducer(){
        System.out.println(environment.getProperty("local.server.port"));
        return "Hello Spring Cloud! - "+environment.getProperty("local.server.port");
    }
}
