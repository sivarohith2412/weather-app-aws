package dev.weather;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.junit.jupiter.api.Assertions.*;

class DynamoWeatherRepositoryTest {
    private final Reading reading = new Reading("us-boston", "Boston", "US", 12.4, 65, 3.1,
            803, "broken clouds", "2026-11-07T14:00:00Z", "2026-11-07T14:02:04Z", "openweather");

    @Test void writesCorrectKeysNumericFieldsAndThirtyDayExpiryWithCondition() {
        var captured = new AtomicReference<PutItemRequest>();
        var client = fake(request -> { captured.set(request); return PutItemResponse.builder().build(); });
        assertEquals(DynamoWeatherRepository.SaveResult.SAVED,
                new DynamoWeatherRepository(client, "weather-readings-dev").save(reading));
        var request = captured.get();
        assertEquals("weather-readings-dev", request.tableName());
        assertEquals("us-boston", request.item().get("cityId").s());
        assertEquals(reading.observedAt(), request.item().get("observedAt").s());
        assertEquals("12.4", request.item().get("temperatureC").n());
        assertEquals(Long.toString(Instant.parse(reading.observedAt()).plus(Duration.ofDays(30)).getEpochSecond()),
                request.item().get("expiresAt").n());
        assertTrue(request.conditionExpression().contains("attribute_not_exists"));
        assertEquals("observedAt", request.expressionAttributeNames().get("#observed"));
    }
    @Test void duplicateObservationIsSuccessfulNoOp() {
        var client = fake(request -> { throw ConditionalCheckFailedException.builder().message("exists").build(); });
        assertEquals(DynamoWeatherRepository.SaveResult.ALREADY_EXISTS,
                new DynamoWeatherRepository(client, "weather-readings-dev").save(reading));
    }
    @Test void permissionErrorsAreNotMistakenForDuplicates() {
        var client = fake(request -> { throw DynamoDbException.builder().message("denied").statusCode(403).build(); });
        assertThrows(DynamoDbException.class,
                () -> new DynamoWeatherRepository(client, "weather-readings-dev").save(reading));
    }
    private DynamoDbClient fake(java.util.function.Function<PutItemRequest, PutItemResponse> write) {
        return (DynamoDbClient) Proxy.newProxyInstance(DynamoDbClient.class.getClassLoader(),
                new Class<?>[]{DynamoDbClient.class}, (proxy, method, args) -> {
                    if (method.getName().equals("putItem")) return write.apply((PutItemRequest) args[0]);
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
