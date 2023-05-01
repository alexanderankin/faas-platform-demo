package org.example.faas;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Arrays;

@Slf4j
@RequiredArgsConstructor
@Service
public class FunctionHandler implements WebFilter {
    final Functions.Repository functionRepository;
    final FunctionInvoker functionInvoker;

    @NonNull
    @Override
    public Mono<Void> filter(@NonNull ServerWebExchange exchange,
                             @NonNull WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();

        checkFirstSegment:
        if (!path.startsWith("/admin-api")) {
            String name = CollectionUtils.firstElement(Arrays.asList(path.substring(1).split("/")));
            if (name == null) break checkFirstSegment;

            return functionRepository.read(name)
                    .doOnNext(f -> log.debug("found function with name: {}", name))
                    .flatMap(function -> functionInvoker.invoke(function, exchange)
                            // todo figure out why cancelled
                            .cache())
                    .switchIfEmpty(Mono.defer(() -> {
                        log.debug("did not find function with name: {}", name);
                        return chain.filter(exchange);
                    }));
        }

        return chain.filter(exchange);
    }
}
