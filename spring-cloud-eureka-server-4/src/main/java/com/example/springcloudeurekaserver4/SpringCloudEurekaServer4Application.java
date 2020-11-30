package com.example.springcloudeurekaserver4;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@SpringBootApplication
@EnableEurekaServer
public class SpringCloudEurekaServer4Application {

    public static void main(String[] args) {
        SpringApplication.run(SpringCloudEurekaServer4Application.class, args);
    }

}
