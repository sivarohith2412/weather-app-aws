package dev.weather;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import org.apache.logging.log4j.CloseableThreadContext;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import software.amazon.lambda.powertools.logging.PowertoolsLogging;
import static software.amazon.lambda.powertools.logging.argument.StructuredArguments.entries;

/** Fans out each scheduled run into a single batch of city jobs. Partial send failures fail the invocation. */
public final class DispatcherHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private final Function<SendMessageBatchRequest, SendMessageBatchResponse> send;
    private final String queueUrl;
    public DispatcherHandler() { this(request -> Resources.CLIENT.sendMessageBatch(request), System.getenv("COLLECTION_QUEUE_URL")); }
    DispatcherHandler(Function<SendMessageBatchRequest, SendMessageBatchResponse> send, String queueUrl) {
        this.send = send; this.queueUrl = queueUrl;
    }
    @Override public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        return PowertoolsLogging.withLogging(context, () -> dispatch(event));
    }
    private Map<String, Object> dispatch(Map<String, Object> event) {
        long started = System.nanoTime();
        String scheduledAt;
        try { scheduledAt = Instant.parse((String) event.get("scheduledAt")).toString(); }
        catch (Exception e) {
            LoggerFactory.getLogger(DispatcherHandler.class).warn("invalid_dispatch_event");
            throw new IllegalArgumentException("Expected scheduledAt as a UTC timestamp");
        }
        String collectionId = "dev-" + scheduledAt;
        try (var scope = CloseableThreadContext.put("collectionId", collectionId).put("scheduledAt", scheduledAt).put("environment", "dev")) {
            var log = LoggerFactory.getLogger(DispatcherHandler.class);
            log.info("dispatch_started", entries(Map.of("cityCount", CityCatalog.all().size())));
            try {
                if (queueUrl == null || queueUrl.isBlank()) throw new IllegalStateException();
                var mapper = new ObjectMapper();
                final String runTime = scheduledAt;
                var jobs = CityCatalog.all().stream().map(city -> {
                    try {
                        return SendMessageBatchRequestEntry.builder().id(city.id()).messageBody(mapper.writeValueAsString(Map.of(
                                "schemaVersion", 1, "collectionId", collectionId, "scheduledAt", runTime, "cityId", city.id()))).build();
                    } catch (Exception e) { throw new IllegalStateException("Cannot serialize collection jobs"); }
                }).toList();
                var response = send.apply(SendMessageBatchRequest.builder().queueUrl(queueUrl).entries(jobs).build());
                if (!response.failed().isEmpty() || response.successful().size() != jobs.size()) {
                    log.error("dispatch_partial_failure", entries(Map.of("failedCount", response.failed().size(),
                            "sentCount", response.successful().size(), "durationMs", CollectionService.elapsed(started))));
                    throw new IllegalStateException("Some city jobs could not be queued");
                }
                log.info("dispatch_completed", entries(Map.of("sentCount", jobs.size(), "durationMs", CollectionService.elapsed(started))));
                return Map.of("collectionId", collectionId, "scheduledAt", scheduledAt, "queuedCityCount", jobs.size());
            } catch (RuntimeException e) {
                log.error("dispatch_failed", entries(Map.of("errorCategory", CollectionService.category(e),
                        "errorType", e.getClass().getSimpleName(), "durationMs", CollectionService.elapsed(started))));
                // Runtime failure is retried by Lambda asynchronous invocation; no raw SDK exception escapes.
                throw new IllegalStateException("Weather dispatch failed");
            }
        }
    }
    private static final class Resources {
        static final SqsClient CLIENT = SqsClient.builder().region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-2")))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(8)).apiCallAttemptTimeout(Duration.ofSeconds(3))).build();
    }
}
