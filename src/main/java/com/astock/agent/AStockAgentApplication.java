package com.astock.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AStockAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AStockAgentApplication.class, args);
    }
}
