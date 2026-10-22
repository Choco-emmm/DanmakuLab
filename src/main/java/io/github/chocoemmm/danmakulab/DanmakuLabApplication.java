package io.github.chocoemmm.danmakulab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DanmakuLabApplication {
    public static void main(String[] args) {
        SpringApplication.run(DanmakuLabApplication.class, args);
    }
}
