package org.example.faas;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;

@Slf4j
@SpringBootApplication
public class FaasApplication {

    @Autowired
    Functions.Repository repository;

    public static void main(String[] args) {
        SpringApplication.run(FaasApplication.class, args);
    }

    @Bean
    ApplicationRunner applicationRunner() {
        return args -> {
            log.info("creating sample function 'name'");
            repository.create(new Functions.Function(null,
                            "name",
                            "hashicorp/http-echo",
                            Arrays.asList("-listen=:8081", "-text=hello world"),
                            8081))
                    .doOnSuccess(f -> log.info("added"))
                    .subscribe();
        };
    }

}
