package dev.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.LoggerFactory;
import static software.amazon.lambda.powertools.logging.argument.StructuredArguments.entries;
import static dev.weather.WeatherException.Category.*;

public final class WeatherClient {
    @FunctionalInterface
    interface Transport { HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException; }
    private final Transport transport;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public WeatherClient() {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        transport = request -> http.send(request, HttpResponse.BodyHandlers.ofString());
        clock = Clock.systemUTC();
    }
    WeatherClient(Transport transport, Clock clock) { this.transport = transport; this.clock = clock; }

    public Reading fetch(City city, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("Set OPENWEATHER_API_KEY first");
        var uri = URI.create("https://api.openweathermap.org/data/2.5/weather?lat=" + city.latitude()
                + "&lon=" + city.longitude() + "&units=metric&appid=" + URLEncoder.encode(apiKey.trim(), StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build();
        long started = System.nanoTime();
        LoggerFactory.getLogger(WeatherClient.class).debug("provider_request_started");
        try {
            var response = transport.send(request);
            LoggerFactory.getLogger(WeatherClient.class).info("provider_response", entries(Map.of(
                    "httpStatus", response.statusCode(), "durationMs", CollectionService.elapsed(started))));
            return decode(city, response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WeatherException(NETWORK);
        } catch (IOException e) {
            // HTTP exceptions may contain the credential-bearing URI. Never propagate them.
            throw new WeatherException(NETWORK);
        }
    }

    Reading decode(City city, int status, String body) {
        if (status == 401 || status == 403) throw new WeatherException(AUTHENTICATION);
        if (status == 429) throw new WeatherException(THROTTLED);
        if (status != 200) throw new WeatherException(PROVIDER);
        try {
            JsonNode root = mapper.readTree(body);
            double temp = number(root.path("main").path("temp"));
            var humidityNode = root.path("main").path("humidity");
            if (!humidityNode.isIntegralNumber()) throw new IllegalArgumentException();
            int humidity = humidityNode.intValue();
            double wind = number(root.path("wind").path("speed"));
            var weather = root.path("weather").path(0);
            if (!weather.path("id").isIntegralNumber() || !weather.path("description").isTextual()
                    || weather.path("description").asText().isBlank() || !root.path("dt").isIntegralNumber()
                    || humidity < 0 || humidity > 100 || wind < 0 || root.path("dt").longValue() <= 0) {
                throw new IllegalArgumentException();
            }
            return new Reading(city.id(), city.name(), city.country(), temp, humidity, wind,
                    weather.path("id").intValue(), weather.path("description").asText(),
                    Instant.ofEpochSecond(root.path("dt").longValue()).toString(), clock.instant().toString(), "openweather");
        } catch (Exception e) { throw new WeatherException(INVALID_RESPONSE); }
    }
    private double number(JsonNode node) {
        if (!node.isNumber() || !Double.isFinite(node.doubleValue())) throw new IllegalArgumentException();
        return node.doubleValue();
    }
}
