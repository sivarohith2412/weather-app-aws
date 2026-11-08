package dev.weather;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WeatherAppHandlerTest {
    private ReadApiHandler reader() {
        return new ReadApiHandler(() -> (city, from, to, limit) -> new WeatherReadStore.Page(List.of(), false),
                Clock.fixed(Instant.parse("2026-11-08T06:00:00Z"), ZoneOffset.UTC));
    }
    @Test void collectsEveryConfiguredCityWithOneStableRunIdentity() {
        var calls = new ArrayList<String>();
        var handler = new WeatherAppHandler(() -> new CollectionService((city, key) -> {
            calls.add(city.id()); return TestFixtures.READING;
        }, r -> DynamoWeatherRepository.SaveResult.ALREADY_EXISTS, () -> "test-key"), reader());
        var response = (Map<?, ?>) handler.process(Map.of("scheduledAt", "2026-11-08T06:00:00Z"));
        assertEquals(CityCatalog.all().stream().map(City::id).toList(), calls);
        assertEquals(CityCatalog.all().size(), response.get("successfulCityCount"));
        assertEquals("dev-2026-11-08T06:00:00Z", response.get("collectionId"));
    }
    @Test void httpRequestsCannotTriggerCollectionThroughTheirBodyOrScheduledAtField() {
        var calls = new AtomicInteger();
        var handler = new WeatherAppHandler(() -> { calls.incrementAndGet(); throw new AssertionError(); }, reader());
        var response = (APIGatewayV2HTTPResponse) handler.process(Map.of("version", "2.0", "routeKey", "GET /weather/latest",
                "requestContext", Map.of("http", Map.of("method", "GET")),
                "body", "{\"scheduledAt\":\"2026-11-08T06:00:00Z\"}", "scheduledAt", "2026-11-08T06:00:00Z"));
        assertEquals(200, response.getStatusCode()); assertEquals(0, calls.get());
    }
    @Test void invalidNonHttpEventsDoNotLoadTheCollector() {
        var handler = new WeatherAppHandler(() -> { throw new AssertionError(); }, reader());
        assertThrows(IllegalArgumentException.class, () -> handler.process(Map.of("scheduledAt", "invalid")));
        assertThrows(IllegalArgumentException.class, () -> handler.process(Map.of("cityId", "us-boston")));
    }
    @Test void oneCityFailureDoesNotPreventAttemptsForTheOtherCitiesAndIsSanitized() {
        var calls = new ArrayList<String>();
        var handler = new WeatherAppHandler(() -> new CollectionService((city, key) -> {
            calls.add(city.id()); if (city.id().equals("us-boston")) throw new IllegalStateException("private-provider-detail");
            return TestFixtures.READING;
        }, r -> DynamoWeatherRepository.SaveResult.SAVED, () -> "test-key"), reader());
        var error = assertThrows(IllegalStateException.class, () -> handler.process(Map.of("scheduledAt", "2026-11-08T06:00:00Z")));
        assertEquals(CityCatalog.all().size(), calls.size());
        assertFalse(error.toString().contains("private-provider-detail")); assertNull(error.getCause());
    }
    @Test void collectorInitializationErrorsDoNotExposeSecretDetails() {
        var handler = new WeatherAppHandler(() -> { throw new IllegalStateException("private-secret-detail"); }, reader());
        var error = assertThrows(IllegalStateException.class, () -> handler.process(Map.of("scheduledAt", "2026-11-08T06:00:00Z")));
        assertFalse(error.toString().contains("private-secret-detail")); assertNull(error.getCause());
    }
}
