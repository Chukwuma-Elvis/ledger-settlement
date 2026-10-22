import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Stand-in for the reference-data service the Ledger settlement service depends on.
 * Serves the currency table (ISO 4217 code, minor-unit digits) as CSV at GET /currencies,
 * after a configurable delay that models a slow remote service.
 *
 *   java perf/reference-data-stub/ReferenceDataStub.java [port=8091] [delayMs=2000]
 */
public class ReferenceDataStub {

    static final String CURRENCIES = "GBP,2\nEUR,2\nUSD,2\nJPY,0\nKWD,3\n";

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8091;
        long delayMs = args.length > 1 ? Long.parseLong(args[1]) : 2000;

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/currencies", exchange -> {
            try {
                Thread.sleep(delayMs); // the remote service is slow, not the caller
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = CURRENCIES.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/csv");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        System.out.printf("reference-data stub on http://localhost:%d/currencies (delay %d ms)%n", port, delayMs);
    }
}
