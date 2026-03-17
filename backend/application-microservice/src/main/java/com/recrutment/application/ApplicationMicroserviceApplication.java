package com.recrutment.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class ApplicationMicroserviceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApplicationMicroserviceApplication.class, args);
    }

}
