package com.karthik.askmychannel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AskMyChannelApplication {

    public static void main(String[] args) {
        SpringApplication.run(AskMyChannelApplication.class, args);
    }
}
