package com.paycore.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** A tiny HTTP server that records webhook POSTs and can be told to fail the first N requests. */
public class WebhookReceiver implements AutoCloseable {

    public record Received(Map<String, String> headers, String body, long receivedAtMillis) {
        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresRemaining = new AtomicInteger();
    private volatile int failStatus = 500;
    private volatile long delayMillis = 0;

    public WebhookReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
            received.add(new Received(headers, body, System.currentTimeMillis()));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            int status = failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0 ? failStatus : 200;
            byte[] resp = (status == 200 ? "ok" : "nope").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    public List<Received> received() {
        return received;
    }

    public void failNext(int n, int status) {
        failStatus = status;
        failuresRemaining.set(n);
    }

    public void delay(long millis) {
        delayMillis = millis;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
