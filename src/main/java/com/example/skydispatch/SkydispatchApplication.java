package com.example.skydispatch;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.example.skydispatch.mapper")
public class SkydispatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkydispatchApplication.class, args);
    }

}
