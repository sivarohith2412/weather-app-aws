package dev.weather;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReadApiHandlerTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-08T05:00:00Z"), ZoneOffset.UTC);
    private APIGatewayV2HTTPEvent event(String route, Map<String, String> query) {
        var e = new APIGatewayV2HTTPEvent(); e.setRouteKey(route); e.setQueryStringParameters(query); return e;
    }
    @Test void rejectsUnknownCityAndUnboundedHistoryWithoutReadingDatabase() {
        var calls = new AtomicInteger();
        var api = new ReadApiHandler(() -> (city, from, to, limit) -> { calls.incrementAndGet(); return new WeatherReadStore.Page(List.of(), false); }, clock);
        assertEquals(400, api.process(event("GET /weather/history", Map.of("cityId", "invalid"))).getStatusCode());
        assertEquals(400, api.process(event("GET /weather/history", Map.of("cityId", "us-boston", "hours", "25"))).getStatusCode());
        assertEquals(0, calls.get());
    }
    @Test void latestReportsMissingCitiesAndBoundsEachQuery() throws Exception {
        var calls = new AtomicInteger();
        var api = new ReadApiHandler(() -> (city, from, to, limit) -> {
            assertEquals(1, limit); assertEquals(clock.instant(), to);
            assertTrue(from.isAfter(to.minus(Duration.ofDays(30))));
            calls.incrementAndGet(); return new WeatherReadStore.Page(List.of(), false);
        }, clock);
        var response = api.process(event("GET /weather/latest", Map.of()));
        assertEquals(200, response.getStatusCode()); assertEquals(5, calls.get());
        assertEquals(5, new ObjectMapper().readTree(response.getBody()).get("missingCityIds").size());
    }
    @Test void serviceErrorsDoNotLeakInternals() {
        var api = new ReadApiHandler(() -> { throw new IllegalStateException("private connection details"); }, clock);
        var response = api.process(event("GET /weather/latest", Map.of()));
        assertEquals(503, response.getStatusCode()); assertFalse(response.getBody().contains("private"));
    }
    @Test void mapsDecimalIntegerAttributesFromExistingWriter() {
        var reading = new Reading("us-boston", "Boston", "US", 11, 90, 2, 701, "mist", "2026-11-08T04:00:00Z", "2026-11-08T04:05:00Z", "openweather");
        assertEquals(reading, DynamoWeatherReader.reading(DynamoWeatherRepository.toItem(reading)));
    }
}
