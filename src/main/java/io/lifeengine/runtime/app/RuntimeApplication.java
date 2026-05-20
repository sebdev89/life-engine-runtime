package io.lifeengine.runtime.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.lifeengine.runtime")
public class RuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuntimeApplication.class, args);
    }
}
