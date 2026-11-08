package dev.weather;

import com.amazonaws.services.lambda.runtime.*;
import com.amazonaws.services.lambda.runtime.events.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import static software.amazon.lambda.powertools.logging.argument.StructuredArguments.entries;
import software.amazon.lambda.powertools.logging.PowertoolsLogging;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Public read-only dashboard API. No provider credentials or collection operations. */
public final class ReadApiHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Supplier<WeatherReadStore> store;
    private final Clock clock;
    public ReadApiHandler() { this(() -> Resources.STORE, Clock.systemUTC()); }
    ReadApiHandler(Supplier<WeatherReadStore> store, Clock clock) { this.store = store; this.clock = clock; }
    @Override public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        return PowertoolsLogging.withLogging(context, () -> process(event));
    }
    APIGatewayV2HTTPResponse process(APIGatewayV2HTTPEvent event) {
        long started = System.nanoTime();
        int status = 200;
        String route = event == null ? "" : Objects.toString(event.getRouteKey(), "");
        try {
            var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            var params = event.getQueryStringParameters() == null ? Map.<String, String>of() : event.getQueryStringParameters();
            Object body;
            switch (route) {
                case "GET /cities" -> body = Map.of("cities", CityCatalog.all());
                case "GET /weather/latest" -> {
                    var readings = new ArrayList<Reading>();
                    var missing = new ArrayList<String>();
                    for (var city : CityCatalog.all()) {
                        var page = store.get().query(city.id(), now.minus(30, ChronoUnit.DAYS).plusSeconds(1), now, 1);
                        if (page.readings().isEmpty()) missing.add(city.id()); else readings.add(page.readings().getFirst());
                    }
                    body = Map.of("readings", readings, "missingCityIds", missing, "generatedAt", now.toString());
                }
                case "GET /weather/history" -> {
                    var city = CityCatalog.find(params.get("cityId")).orElseThrow(IllegalArgumentException::new);
                    int hours = Integer.parseInt(params.getOrDefault("hours", "24"));
                    if (hours < 1 || hours > 24) throw new IllegalArgumentException();
                    var from = now.minus(hours, ChronoUnit.HOURS);
                    var page = store.get().query(city.id(), from, now, 500);
                    var ordered = new ArrayList<>(page.readings());
                    ordered.sort(Comparator.comparing(Reading::observedAt));
                    body = Map.of("cityId", city.id(), "readings", ordered, "truncated", page.truncated(),
                            "from", from.toString(), "to", now.toString());
                }
                default -> { status = 404; body = Map.of("message", "Route not found"); }
            }
            return response(status, body);
        } catch (IllegalArgumentException e) {
            status = 400;
            return response(status, Map.of("message", "Provide a configured cityId and hours from 1 to 24"));
        } catch (Exception | ExceptionInInitializerError e) {
            status = 503;
            LoggerFactory.getLogger(ReadApiHandler.class).error("weather_read_failed");
            return response(status, Map.of("message", "Weather readings are temporarily unavailable"));
        } finally {
            LoggerFactory.getLogger(ReadApiHandler.class).info("weather_api_completed", entries(Map.of(
                    "route", Set.of("GET /cities", "GET /weather/latest", "GET /weather/history").contains(route) ? route : "unknown",
                    "httpStatus", status, "durationMs", CollectionService.elapsed(started))));
        }
    }
    private static APIGatewayV2HTTPResponse response(int status, Object body) {
        try { return APIGatewayV2HTTPResponse.builder().withStatusCode(status)
                .withHeaders(Map.of("Content-Type", "application/json", "Cache-Control", "no-store", "X-Content-Type-Options", "nosniff"))
                .withBody(JSON.writeValueAsString(body)).build(); }
        catch (Exception e) { throw new IllegalStateException("Response serialization failed"); }
    }
    private static final class Resources {
        static final WeatherReadStore STORE = new DynamoWeatherReader(DynamoDbClient.builder()
                .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-2")))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(3)).apiCallAttemptTimeout(Duration.ofSeconds(2))).build(),
                System.getenv().getOrDefault("WEATHER_TABLE_NAME", "weather-readings-dev"));
    }
}
