package io.github.sumukhesh.secmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SecMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecMcpServerApplication.class, args);
    }
}
