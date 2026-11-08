package dev.weather;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CollectorHandlerTest {
    static final Reading READING = new Reading("us-boston", "Boston", "US", 12.4, 65, 3.1, 803,
            "clouds", "2026-11-07T14:00:00Z", "2026-11-07T14:02:00Z", "openweather");
    @Test void storesValidJobAndReturnsSafeResult() {
        var handler = handler(r -> DynamoWeatherRepository.SaveResult.SAVED);
        var response = handler.handleRequest(Map.of("schemaVersion", 1, "cityId", "us-boston", "collectionId", "dev-test-1"), context());
        assertEquals("SAVED", response.get("outcome"));
        assertEquals("dev-test-1", response.get("collectionId"));
        assertEquals(READING.observedAt(), response.get("observedAt"));
    }
    @Test void duplicatesAreSuccessful() {
        assertEquals("ALREADY_EXISTS", handler(r -> DynamoWeatherRepository.SaveResult.ALREADY_EXISTS)
                .handleRequest(Map.of("schemaVersion", 1, "cityId", "us-boston"), context()).get("outcome"));
    }
    @Test void invalidJobDoesNotLoadCredentialsOrCallProvider() {
        var calls = new AtomicInteger();
        var handler = new CollectorHandler(() -> { calls.incrementAndGet(); throw new AssertionError(); });
        for (var event : java.util.List.of(Map.<String,Object>of("cityId", "unconfigured", "schemaVersion", 1),
                Map.<String,Object>of("cityId", "us-boston", "schemaVersion", 2),
                Map.<String,Object>of("cityId", "us-boston", "schemaVersion", 1, "collectionId", "unsafe\nvalue"))) {
            assertThrows(IllegalArgumentException.class, () -> handler.handleRequest(event, context()));
        }
        assertEquals(0, calls.get());
    }
    @Test void failuresAreRethrownWithoutRawSecretOrExceptionCause() {
        var error = assertThrows(CollectionService.CollectionFailure.class,
                () -> handler(r -> { throw new IllegalStateException("private-api-key"); })
                        .handleRequest(Map.of("schemaVersion", 1, "cityId", "us-boston"), context()));
        assertFalse(error.toString().contains("private-api-key"));
        assertNull(error.getCause());
    }
    private CollectorHandler handler(Function<Reading, DynamoWeatherRepository.SaveResult> save) {
        return new CollectorHandler(() -> new CollectionService((city,key) -> READING, save, () -> "private-api-key"));
    }
    static Context context() {
        return (Context) Proxy.newProxyInstance(Context.class.getClassLoader(), new Class<?>[]{Context.class}, (p,m,a) -> {
            return switch (m.getName()) {
                case "getMemoryLimitInMB" -> 512;
                case "getRemainingTimeInMillis" -> 45000;
                case "getAwsRequestId" -> "test-request-id";
                case "getFunctionName" -> "weather-collector-dev";
                case "getFunctionVersion" -> "$LATEST";
                case "getInvokedFunctionArn" -> "arn:aws:lambda:us-east-2:471084227270:function:weather-collector-dev";
                case "getLogger" -> new LambdaLogger() {
                    public void log(String message) {}
                    public void log(byte[] message) {}
                };
                default -> "test";
            };
        });
    }
}
