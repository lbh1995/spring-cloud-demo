package com.example.springcloudeurekaserver3;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@SpringBootApplication
@EnableEurekaServer
public class SpringCloudEurekaServer3Application {

    public static void main(String[] args) {
        SpringApplication.run(SpringCloudEurekaServer3Application.class, args);
    }

}
