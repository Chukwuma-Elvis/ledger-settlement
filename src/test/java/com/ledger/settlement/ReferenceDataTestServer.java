package com.ledger.settlement;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** In-process stand-in for the reference-data service, on a random free port, no delay. */
final class ReferenceDataTestServer {

    private ReferenceDataTestServer() {
    }

    /** Starts the server and returns the URL of its currency table. */
    static String start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/currencies", exchange -> {
                byte[] body = "GBP,2\nEUR,2\nUSD,2\nJPY,0\n".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return "http://localhost:" + server.getAddress().getPort() + "/currencies";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
