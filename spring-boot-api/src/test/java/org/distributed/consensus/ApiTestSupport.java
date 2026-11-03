package org.distributed.consensus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Starts a PostgreSQL container (shared by all tests) and real api instances against it,
 * so the tests run the same code paths as the deployed api: http -> controller -> service -> database.
 */
public abstract class ApiTestSupport {

    // started once for all test classes, removed by testcontainers when the jvm exits
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.2-alpine");

    static {
        POSTGRES.start();
    }

    // every test uses its own rooms, so the tests do not influence each other
    private static final AtomicLong NEXT_ROOM_ID = new AtomicLong(1000);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final ObjectMapper JSON = new ObjectMapper();

    protected static ConfigurableApplicationContext startInstance(String... extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword()));
        args.addAll(List.of(extraArgs));
        return new SpringApplicationBuilder(Application.class).run(args.toArray(String[]::new));
    }

    protected static String baseUrl(ConfigurableApplicationContext instance) {
        int port = ((WebServerApplicationContext) instance).getWebServer().getPort();
        return "http://localhost:" + port;
    }

    protected static JdbcTemplate jdbc(ConfigurableApplicationContext instance) {
        return instance.getBean(JdbcTemplate.class);
    }

    protected static long newRoomId() {
        return NEXT_ROOM_ID.incrementAndGet();
    }

    // ---- http calls of the booking process ------------------------------------------------

    protected static HttpResponse<String> reserve(String baseUrl, String variant, long roomId, String start, String finish) {
        String body = """
                {"name": "Test Room", "roomId": %d, "start": "%s", "finish": "%s"}
                """.formatted(roomId, start, finish);
        return post(baseUrl + "/" + variant + "/booking", body);
    }

    protected static HttpResponse<String> confirm(String baseUrl, String variant, long bookingId) {
        return post(baseUrl + "/" + variant + "/bookingconfirmation", "{\"bookingId\": " + bookingId + "}");
    }

    protected static HttpResponse<String> delete(String baseUrl, String variant, long bookingId) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/" + variant + "/booking/" + bookingId)).DELETE());
    }

    protected static long idOf(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body()).get("id").asLong();
        } catch (Exception e) {
            throw new IllegalStateException("no id in response " + response.statusCode() + " " + response.body(), e);
        }
    }

    private static HttpResponse<String> post(String url, String json) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return HTTP.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- parallel execution ---------------------------------------------------------------

    /**
     * Runs the task n times in parallel and returns the http status codes in the order of the task index.
     * All threads wait at a start gate, so the requests really hit the api at the same time.
     */
    protected static List<Integer> inParallel(int n, IntFunction<HttpResponse<String>> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.apply(index).statusCode();
                }));
            }
            ready.await();
            start.countDown();

            List<Integer> statusCodes = new ArrayList<>();
            for (Future<Integer> result : results) {
                statusCodes.add(result.get());
            }
            return statusCodes;
        } finally {
            executor.shutdownNow();
        }
    }

    protected static long count(List<Integer> statusCodes, int statusCode) {
        return statusCodes.stream().filter(code -> code == statusCode).count();
    }

    protected static Stream<String> variants() {
        return Stream.of("optimistic", "pessimistic");
    }
}
