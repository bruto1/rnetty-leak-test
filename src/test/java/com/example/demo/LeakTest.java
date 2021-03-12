package com.example.demo;

import io.vavr.control.Try;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = LeakTest.Cfg.class)
@Log4j2
class LeakTest {
    @LocalServerPort
    protected int port;

    @Test
    void test() throws Exception {
        int reps = 100;
        var latch = new CountDownLatch(reps);

        var client = getWsClient(false);
        var uri = URI.create("ws://localhost:" + port + "/echo");

        IntStream.range(0, reps).forEach(__ ->
            Schedulers.parallel().schedule(() ->
                Try.run(() ->
                    ping(client, uri)
                        .doAfterTerminate(latch::countDown)
                        .subscribe(log::info))
                    .onFailure(log::error)
            )
        );

        latch.await(1, TimeUnit.MINUTES);
        log.info("now is a good time to make a heap dump");
    }

    Mono<String> ping(WebSocketClient client, URI uri) {
        return requestChannel(client, uri, Flux.just("ping"))
            .map(WebSocketMessage::getPayloadAsText)
            .next();
    }

    private static Flux<WebSocketMessage> requestChannel(WebSocketClient wsClient, URI uri, Flux<String> outbound) {
        final CompletableFuture<Flux<WebSocketMessage>> recvFuture = new CompletableFuture<>();
        final CompletableFuture<Integer> consumerDoneCallback = new CompletableFuture<>();

        final WebSocketHandler handler = wss -> {
            recvFuture.complete(wss.receive());
            return wss.send(outbound.map(wss::textMessage))
                .and(Mono.fromFuture(consumerDoneCallback));
        };
        final Mono<Void> executeMono = wsClient.execute(uri, handler);

        return Mono.fromFuture(recvFuture)
            .flatMapMany(recv -> recv.doOnComplete(() -> consumerDoneCallback.complete(1)))
            .mergeWith(executeMono.cast(WebSocketMessage.class));
    }

    private static ReactorNettyWebSocketClient getWsClient(boolean usePooledConnections) {
        return new ReactorNettyWebSocketClient(
            (usePooledConnections
                ? HttpClient.create()
                : HttpClient.create(ConnectionProvider.newConnection())
            )
                .metrics(true, uri -> uri));
    }


    @SpringBootConfiguration
    @EnableAutoConfiguration
    public static class Cfg {
        @Bean
        public ReactiveWebServerFactory reactiveWebServerFactory() {
            return new NettyReactiveWebServerFactory();
        }

        @Bean
        public WebSocketHandler echoHandler() {
            return session ->
                session.send(session.receive()
                    .map(WebSocketMessage::getPayloadAsText)
                    .doOnNext(log::info)
                    .map(ping -> session.textMessage("pong"))
                );
        }

        @Bean
        WebSocketHandlerAdapter webSocketHandlerAdapter() {
            final ReactorNettyRequestUpgradeStrategy reactorNettyRequestUpgradeStrategy = new ReactorNettyRequestUpgradeStrategy();
            var handshakeWebSocketService = new HandshakeWebSocketService(reactorNettyRequestUpgradeStrategy);

            return new WebSocketHandlerAdapter(handshakeWebSocketService);
        }

        @Bean
        HandlerMapping handlerMapping(WebSocketHandler h) {
            return new SimpleUrlHandlerMapping(Map.of("/echo", h), Ordered.HIGHEST_PRECEDENCE);
        }
    }
}
