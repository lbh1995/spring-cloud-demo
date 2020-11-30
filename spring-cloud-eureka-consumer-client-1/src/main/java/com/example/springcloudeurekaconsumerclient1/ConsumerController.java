package com.example.springcloudeurekaconsumerclient1;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@Slf4j
@Configuration
public class ConsumerController {
    private final RestTemplate restTemplate;
    @Autowired
    public ConsumerController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }
    @GetMapping("/hello/consumer")
    public String helloConsumer() {
        String result = restTemplate.getForEntity("http://PRODUCER-SERVICE/hello/producer", String.class).getBody();
        System.out.println("远程调用返回结果是："+result);
        return result;
    }
}
