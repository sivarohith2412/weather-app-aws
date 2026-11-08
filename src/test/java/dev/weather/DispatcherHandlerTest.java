package dev.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.*;
import static org.junit.jupiter.api.Assertions.*;

class DispatcherHandlerTest {
    private static final Map<String,Object> EVENT = Map.of("scheduledAt", "2026-11-07T15:00:00Z");
    @Test void sendsOneJobPerConfiguredCityWithStableRunIdentity() throws Exception {
        var captured = new AtomicReference<SendMessageBatchRequest>();
        var handler = new DispatcherHandler(request -> {
            captured.set(request);
            return SendMessageBatchResponse.builder().successful(request.entries().stream()
                    .map(e -> SendMessageBatchResultEntry.builder().id(e.id()).messageId(e.id()).build()).toList()).build();
        }, "https://sqs.us-east-2.amazonaws.com/471084227270/weather-collection-dev");
        var first = handler.handleRequest(EVENT, CollectorHandlerTest.context());
        var firstBodies = captured.get().entries().stream().map(SendMessageBatchRequestEntry::messageBody).toList();
        assertEquals(CityCatalog.all().size(), first.get("queuedCityCount"));
        assertEquals(CityCatalog.all().size(), new HashSet<>(captured.get().entries().stream().map(SendMessageBatchRequestEntry::id).toList()).size());
        for (var entry : captured.get().entries()) {
            var job = new ObjectMapper().readTree(entry.messageBody());
            assertEquals(1, job.path("schemaVersion").asInt());
            assertEquals(entry.id(), job.path("cityId").asText());
            assertEquals(first.get("collectionId"), job.path("collectionId").asText());
        }
        assertEquals(first, handler.handleRequest(EVENT, CollectorHandlerTest.context()));
        assertEquals(firstBodies, captured.get().entries().stream().map(SendMessageBatchRequestEntry::messageBody).toList());
    }
    @Test void httpSuccessWithPartialBatchFailureFailsDispatch() {
        var handler = new DispatcherHandler(request -> SendMessageBatchResponse.builder()
                .failed(BatchResultErrorEntry.builder().id("gb-london").code("InternalError").senderFault(false).build()).build(), "queue");
        assertThrows(IllegalStateException.class, () -> handler.handleRequest(EVENT, CollectorHandlerTest.context()));
    }
    @Test void invalidScheduledTimestampDoesNotSendAnything() {
        var handler = new DispatcherHandler(request -> { fail("must not send"); return null; }, "queue");
        assertThrows(IllegalArgumentException.class, () -> handler.handleRequest(Map.of("scheduledAt", "bad"), CollectorHandlerTest.context()));
    }
    @Test void everyConfiguredCityCanBeCollectedWithoutTrustingCallerCoordinates() {
        var actual = new AtomicReference<City>();
        var handler = new CollectorHandler(() -> new CollectionService((city,key) -> {
            actual.set(city); return CollectorHandlerTest.READING;
        }, r -> DynamoWeatherRepository.SaveResult.SAVED, () -> "test-key"));
        for (var city : CityCatalog.all()) {
            var result = handler.handleRequest(Map.of("schemaVersion", 1, "cityId", city.id(), "latitude", 0), CollectorHandlerTest.context());
            assertEquals(city, actual.get());
            assertEquals(city.id(), result.get("cityId"));
        }
    }
}

