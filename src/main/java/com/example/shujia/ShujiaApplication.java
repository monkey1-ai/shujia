package com.example.shujia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
public class ShujiaApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShujiaApplication.class, args);
    }
}
