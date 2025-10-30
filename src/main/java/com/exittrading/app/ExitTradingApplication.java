package com.exittrading.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ExitTradingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExitTradingApplication.class, args);
    }
}
