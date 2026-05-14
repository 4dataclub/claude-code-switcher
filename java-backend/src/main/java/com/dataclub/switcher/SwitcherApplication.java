package com.dataclub.switcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SwitcherApplication {
    public static void main(String[] args) {
        SpringApplication.run(SwitcherApplication.class, args);
    }
}
