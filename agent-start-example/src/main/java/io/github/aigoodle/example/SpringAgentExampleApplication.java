package io.github.aigoodle.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Runnable demo that wires the model, knowledge and workflow modules together.
 * See {@link DemoController} for endpoints exercising each module.
 */
@SpringBootApplication
public class SpringAgentExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAgentExampleApplication.class, args);
    }
}
