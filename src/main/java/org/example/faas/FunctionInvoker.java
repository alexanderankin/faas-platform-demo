package org.example.faas;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;

import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RequiredArgsConstructor
@Service
public class FunctionInvoker {
    final WebClient.Builder builder;
    final DockerClient dockerClient;
    final ConcurrentHashMap<Runner, Integer> concurrency = new ConcurrentHashMap<>();
    WebClient webClient;

    private static Mono<Tuple2<Runner, ServerWebExchange>> apply(Tuple2<Runner, ServerWebExchange> runner) {
        return runner.getT1().run(runner.getT2()).thenReturn(runner);
    }

    @PostConstruct
    void init() {
        webClient = builder.build();
    }

    public Mono<Void> invoke(Functions.Function function,
                             ServerWebExchange exchange) {
        return start(function).zipWith(Mono.just(exchange))
                .flatMap(FunctionInvoker::apply)
                .map(Tuple2::getT1)
                .flatMap(this::decrement);
    }

    private synchronized Mono<Void> decrement(Runner runner) {
        log.debug("decrementing runner for function: {}", runner.getFunction().getName());
        Integer instances = concurrency.get(runner);
        log.debug("decrementing runner for function: {}, instances: {}", runner.getFunction().getName(), instances);
        if (instances == null)
            return runner.stop();

        if (instances == 1) {
            log.debug("decrementing runner for function: {}, removing last", runner.getFunction().getName());
            concurrency.remove(runner);
            return runner.stop();
        }

        concurrency.put(runner, instances - 1);
        return Mono.empty();
    }

    private synchronized Mono<Runner> start(Functions.Function function) {
        Runner runner = new Runner(function);
        // atomic operation in redis?
        concurrency.put(runner, concurrency.getOrDefault(runner, 0) + 1);
        return runner.start().thenReturn(runner);
    }

    @Data
    class Runner {
        private static final Set<HttpMethod> NON_BODY_METHODS = Set.of(HttpMethod.GET,
                HttpMethod.DELETE,
                HttpMethod.TRACE,
                HttpMethod.OPTIONS,
                HttpMethod.HEAD);
        final Functions.Function function;
        Mono<Void> start;
        String containerId;
        Integer port;

        public Mono<Void> start() {
            if (this.start != null) throw new IllegalStateException("already started: " + this);

            return this.start = Mono.<Void>fromRunnable(this::startIt)
                    .cache()
                    .publishOn(Schedulers.boundedElastic());
        }

        @SneakyThrows
        private void startIt() {
            ExposedPort exposedPort = ExposedPort.tcp(function.getPort());

            CreateContainerCmd createCmd = dockerClient.createContainerCmd(function.getCoordinates())
                    .withCmd(Objects.requireNonNullElse(function.getArguments(), Collections.emptyList()))
                    .withExposedPorts(exposedPort)
                    ;
            createCmd.withHostConfig(Objects.requireNonNullElseGet(createCmd.getHostConfig(), HostConfig::new)
                            .withPortBindings(new PortBinding(Ports.Binding.empty(), exposedPort)));
            var createContainerResponse = createCmd.exec();

            containerId = createContainerResponse.getId();
            log.debug("created container with id: {}", containerId);
            dockerClient.startContainerCmd(containerId).exec();

            int counter = 10;
            while (counter-- > 0) {
                try {
                    var bindings = dockerClient.inspectContainerCmd(containerId).exec().getNetworkSettings().getPorts().getBindings();
                    var portBindings = bindings.get(exposedPort);
                    if (portBindings == null) throw new IllegalStateException();
                    var portBindingsList = Arrays.asList(portBindings);
                    var first = CollectionUtils.firstElement(portBindingsList);
                    if (first == null) throw new IllegalStateException();
                    // single port limitation - getHostPortSpec can return range of "start-end"
                    port = Integer.parseInt(first.getHostPortSpec());
                    if (!connectable(port)) throw new IllegalStateException();
                    else return;
                } catch (NotFoundException | IllegalStateException | NumberFormatException e) {
                    log.debug("not bound yet: {}", e.getMessage());
                    Thread.sleep(5000);
                }
            }
        }

        public Mono<Void> run(ServerWebExchange exchange) {
            return start.then(Mono.defer(() -> proxy(exchange)));
        }

        public Mono<Void> proxy(ServerWebExchange exchange) {
            ServerHttpRequest request = exchange.getRequest();
            WebClient.RequestBodyUriSpec clientCall1 = webClient.method(request.getMethod());
            WebClient.RequestBodySpec clientCall2 = clientCall1.uri(clientUri(request.getURI(), "localhost", port));

            WebClient.RequestHeadersSpec<?> clientCall3;
            if (NON_BODY_METHODS.contains(request.getMethod())) clientCall3 = clientCall2;
            else clientCall3 = clientCall2.body(exchange.getRequest().getBody(), DataBuffer.class);

            var clientCall4 = clientCall3.headers(h -> h.addAll(request.getHeaders()));

            ServerHttpResponse response = exchange.getResponse();
            return clientCall4.exchangeToMono(clientResponse -> {
                response.setStatusCode(clientResponse.statusCode());
                response.getHeaders().addAll(clientResponse.headers().asHttpHeaders());
                return response.writeWith(clientResponse.bodyToFlux(DataBuffer.class));
            });
        }

        @SuppressWarnings("SameParameterValue")
        @SneakyThrows
        private URI clientUri(URI uri, String host, int port) {
            return new URI(
                    uri.getScheme(),
                    uri.getRawUserInfo(),
                    host,
                    port,
                    uri.getRawPath(),
                    uri.getRawQuery(),
                    uri.getRawFragment()
            );
        }

        private boolean connectable(Integer port) {
            try {
                new Socket(InetAddress.getLocalHost(), port).close();
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        public Mono<Void> stop() {
            log.debug("runner is stopping for function: {} (containerId: {})", function.getName(), this.containerId);
            this.start = null;
            var containerId = this.containerId;
            this.containerId = null;
            this.port = null;
            // if (this.containerId == null) throw new IllegalStateException("can't stop - not running");

            if (containerId == null) {
                log.debug("runner has no container id so cannot stop function: {}", function.getName());
                return Mono.empty();
            }

            return Mono.<Void>fromRunnable(() -> {
                        try {
                            log.debug("runner is calling docker to stop function: {}", function.getName());
                            dockerClient.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
                            log.debug("runner told docker to stop function: {}", function.getName());
                        } catch (NotFoundException e) {
                            log.debug("deleted {} multiple times? not found...", containerId, e);
                        }
                    })
                    .publishOn(Schedulers.boundedElastic())
                    .doOnTerminate(() -> log.debug("runner is done stopping function: {}", function.getName()))
                    ;
        }
    }
}
