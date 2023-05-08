package org.example.faas;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

public class Functions {
    @Builder(toBuilder = true, access = AccessLevel.PRIVATE)
    @Value
    static class Function {
        UUID id;
        @NotNull
        String name;
        @NotNull
        String coordinates;
        List<String> arguments;
        @NotNull
        Integer port;
        @NotNull
        Duration instanceTimeout = Duration.ofMinutes(1);

        /**
         * how many requests can use the same function instance, or &lt;= 0 for no limit
         */
        @NotNull
        Integer concurrency = -1;

        @PositiveOrZero
        @NotNull
        Integer minInstances = 0;

        @Positive
        @NotNull
        Integer maxInstances = 1;
    }

    @SuppressWarnings("UnusedReturnValue")
    @Component
    @Validated
    static class Repository {
        Map<UUID, Function> data = new LinkedHashMap<>();
        Map<String, UUID> names = new HashMap<>();

        Mono<Function> create(@Valid Function function) {
            if (names.containsKey(function.getName()))
                throw new IllegalStateException("already exists");

            UUID uuid = UUID.randomUUID();
            Function saved = function.toBuilder().id(uuid).build();
            names.put(saved.getName(), uuid);
            data.put(uuid, saved);
            return Mono.justOrEmpty(saved);
        }

        Mono<Function> read(@NotNull UUID id) {
            return Mono.justOrEmpty(data.get(id));
        }

        Mono<Function> read(@NotNull String name) {
            return Mono.justOrEmpty(Optional.ofNullable(names.get(name))
                    .map(data::get)
                    .orElse(null));
        }

        Mono<Function> update(@Valid Function body, @NotNull UUID id) {
            Function function = body.toBuilder().id(id).build();
            Function old = data.get(id);
            if (old == null) throw new IllegalStateException("no such function: " + id);
            data.put(id, function);
            names.remove(old.getName());
            names.put(function.getName(), function.getId());
            return Mono.justOrEmpty(function);
        }

        Mono<Function> delete(@NotNull UUID id) {
            Function old = data.get(id);
            if (old == null) throw new IllegalStateException("no such function: " + id);
            names.remove(old.getName());
            data.remove(id);
            return Mono.justOrEmpty(old);
        }
    }

    @RestController
    @RequestMapping("/admin-api/functions")
    static class Controller {

    }
}
