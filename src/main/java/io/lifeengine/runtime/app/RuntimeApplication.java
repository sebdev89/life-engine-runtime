package io.lifeengine.runtime.app;

import io.lifeengine.runtime.llm.RuntimeLlmProperties;
import io.lifeengine.runtime.security.RuntimeJwksProperties;
import io.lifeengine.runtime.security.RuntimeJwtProperties;
import io.lifeengine.runtime.security.RuntimeSecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = "io.lifeengine.runtime")
@EnableConfigurationProperties({
    RuntimeSecurityProperties.class,
    RuntimeJwtProperties.class,
    RuntimeJwksProperties.class,
    RuntimeLlmProperties.class
})
public class RuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuntimeApplication.class, args);
    }
}
