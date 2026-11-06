package com.example.summer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
public class SummerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SummerApplication.class, args);
    }
}
