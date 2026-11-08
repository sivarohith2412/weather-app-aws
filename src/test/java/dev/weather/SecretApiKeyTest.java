package dev.weather;

import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecretApiKeyTest {
    @Test void cachesWarmSecretAndRefreshesAfterFiveMinutes() {
        var clock = new MutableClock();
        var calls = new AtomicInteger();
        var key = new SecretApiKey(() -> "{\"OPENWEATHER_API_KEY\":\"key-" + calls.incrementAndGet() + "\"}", clock);
        assertEquals("key-1", key.get());
        assertEquals("key-1", key.get());
        clock.now = clock.now.plusSeconds(301);
        assertEquals("key-2", key.get());
        assertEquals(2, calls.get());
    }
    @Test void invalidSecretsDoNotLeakInErrors() {
        var key = new SecretApiKey(() -> "private-key-not-json", Clock.systemUTC());
        var e = assertThrows(IllegalStateException.class, key::get);
        assertFalse(e.toString().contains("private-key-not-json"));
        assertNull(e.getCause());
    }
    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-07T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
