package dev.weather;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.weather.WeatherException.Category.*;

class WeatherClientTest {
    private final City city = new City("us-boston", "Boston", "US", 42.3601, -71.0589);
    private final WeatherClient client = new WeatherClient(request -> { throw new IOException(); },
            Clock.fixed(Instant.parse("2026-11-07T15:00:00Z"), ZoneOffset.UTC));
    private final String valid = """
        {"main":{"temp":12.4,"humidity":65},"wind":{"speed":3.1},
         "weather":[{"id":803,"description":"broken clouds"}],"dt":1794052800}
        """;

    @Test void normalizesWeatherAndPreservesBothTimestamps() {
        var reading = client.decode(city, 200, valid);
        assertEquals(12.4, reading.temperatureC());
        assertEquals(65, reading.humidityPercent());
        assertEquals(3.1, reading.windSpeedMps());
        assertEquals(803, reading.conditionCode());
        assertEquals("broken clouds", reading.condition());
        assertEquals("us-boston", reading.cityId());
        assertEquals(Instant.ofEpochSecond(1794052800).toString(), reading.observedAt());
        assertEquals("2026-11-07T15:00:00Z", reading.collectedAt());
    }
    @Test void classifiesProviderErrorsWithoutExposingBody() {
        int[] statuses = {401, 403, 429, 500};
        var categories = new WeatherException.Category[]{AUTHENTICATION, AUTHENTICATION, THROTTLED, PROVIDER};
        for (int i = 0; i < statuses.length; i++) {
            int status = statuses[i];
            var error = assertThrows(WeatherException.class, () -> client.decode(city, status, "private-key"));
            assertEquals(categories[i], error.category());
            assertFalse(error.getMessage().contains("private-key"));
        }
    }
    @Test void rejectsMalformedOrIncompleteReadings() {
        for (String body : new String[]{"broken", "{}", valid.replace("65", "101"),
                valid.replace("3.1", "-1"), valid.replace("12.4", "null"), valid.replace("803", "null")}) {
            assertEquals(INVALID_RESPONSE, assertThrows(WeatherException.class,
                    () -> client.decode(city, 200, body)).category());
        }
    }
    @Test void stripsCredentialBearingNetworkErrors() {
        var unsafe = new WeatherClient(request -> { throw new IOException(request.uri().toString()); }, Clock.systemUTC());
        var error = assertThrows(WeatherException.class, () -> unsafe.fetch(city, "private-key"));
        assertEquals(NETWORK, error.category());
        assertNull(error.getCause());
        assertFalse(error.toString().contains("private-key"));
    }
    @Test void requiresKeyBeforeSending() {
        assertThrows(IllegalArgumentException.class, () -> client.fetch(city, " "));
    }
}
