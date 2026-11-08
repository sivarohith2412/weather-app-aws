package dev.weather;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqsCollectorHandlerTest {
    private static final String JOB = "{\"schemaVersion\":1,\"cityId\":\"us-boston\"}";
    @Test void successfulAndDuplicateMessagesAreAcknowledged() {
        var saves = new AtomicInteger();
        var handler = handler(saves);
        assertTrue(handler.handleRequest(event(message("first", JOB)), CollectorHandlerTest.context()).getBatchItemFailures().isEmpty());
        assertTrue(handler.handleRequest(event(message("first", JOB)), CollectorHandlerTest.context()).getBatchItemFailures().isEmpty());
        assertEquals(2, saves.get());
    }
    @Test void mixedBatchRetriesOnlyFailedMessageAndContinuesProcessing() {
        var saves = new AtomicInteger();
        var response = handler(saves).handleRequest(event(message("good-1", JOB), message("bad", "private-secret-not-json"),
                message("good-2", JOB)), CollectorHandlerTest.context());
        assertEquals(1, response.getBatchItemFailures().size());
        assertEquals("bad", response.getBatchItemFailures().getFirst().getItemIdentifier());
        assertEquals(2, saves.get());
    }
    @Test void invalidCityReturnsFailureWithoutCallingProvider() {
        var saves = new AtomicInteger();
        var response = handler(saves).handleRequest(event(message("unknown-city",
                "{\"schemaVersion\":1,\"cityId\":\"unknown\"}")), CollectorHandlerTest.context());
        assertEquals("unknown-city", response.getBatchItemFailures().getFirst().getItemIdentifier());
        assertEquals(0, saves.get());
    }
    @Test void providerFailureReturnsMessageForRetry() {
        var collector = new CollectorHandler(() -> new CollectionService((city,key) -> {
            throw new WeatherException(WeatherException.Category.THROTTLED);
        }, r -> DynamoWeatherRepository.SaveResult.SAVED, () -> "private-key"));
        var response = new SqsCollectorHandler(collector).handleRequest(event(message("retry-me", JOB)), CollectorHandlerTest.context());
        assertEquals("retry-me", response.getBatchItemFailures().getFirst().getItemIdentifier());
    }
    @Test void envelopeWithoutMessageIdFailsInsteadOfAcknowledging() {
        assertThrows(IllegalArgumentException.class, () -> handler(new AtomicInteger())
                .handleRequest(event(message(null, JOB)), CollectorHandlerTest.context()));
    }
    private SqsCollectorHandler handler(AtomicInteger saves) {
        return new SqsCollectorHandler(new CollectorHandler(() -> new CollectionService((city,key) -> CollectorHandlerTest.READING,
                r -> saves.incrementAndGet() == 1 ? DynamoWeatherRepository.SaveResult.SAVED : DynamoWeatherRepository.SaveResult.ALREADY_EXISTS,
                () -> "private-key")));
    }
    private SQSEvent event(SQSEvent.SQSMessage... messages) {
        var event = new SQSEvent(); event.setRecords(List.of(messages)); return event;
    }
    private SQSEvent.SQSMessage message(String id, String body) {
        var message = new SQSEvent.SQSMessage(); message.setMessageId(id); message.setBody(body);
        message.setEventSource("aws:sqs"); message.setEventSourceArn("arn:aws:sqs:us-east-2:471084227270:weather-collection-dev");
        message.setAttributes(Map.of("ApproximateReceiveCount", "1")); return message;
    }
}
