package com.example.springcloudeurekaproducerclient2;

import com.example.springcloudeurekaproducerclient2.grpc_test.GRPCPowerPredictClient;
import com.example.springcloudeurekaproducerclient2.grpc_test.HelloWorldClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Slf4j
public class ProducerController {
    private final DiscoveryClient dc;
    @Autowired
    public ProducerController(DiscoveryClient discoveryClient){
        this.dc = discoveryClient;
    }
    @Autowired
    Environment environment;
    @GetMapping("/hello/producer")
    public String helloProducer(){
        List<String> services = dc.getServices();
        System.out.println(environment.getProperty("local.server.port"));
        GRPCPowerPredictClient client = new GRPCPowerPredictClient("192.168.0.84", 50051);
        String res = client.greet("lbhmbp","1","100");
        //HelloWorldClient client = new HelloWorldClient("192.168.0.84", 50051);
        //String res = client.greet("lbhmbp");
        return res + " - " + environment.getProperty("local.server.port");
    }
}
