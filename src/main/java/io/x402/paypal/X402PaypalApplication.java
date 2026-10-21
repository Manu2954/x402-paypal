package io.x402.paypal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class X402PaypalApplication {

    public static void main(String[] args) {
        SpringApplication.run(X402PaypalApplication.class, args);
    }
}
