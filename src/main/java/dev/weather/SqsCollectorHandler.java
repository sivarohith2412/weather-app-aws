package dev.weather;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.apache.logging.log4j.CloseableThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.powertools.batch.BatchMessageHandlerBuilder;
import software.amazon.lambda.powertools.batch.handler.BatchMessageHandler;
import software.amazon.lambda.powertools.logging.PowertoolsLogging;
import static software.amazon.lambda.powertools.logging.argument.StructuredArguments.entries;

/** Standard SQS queue only. Failed identifiers are returned for later SQS retries. */
public final class SqsCollectorHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {
    private static final Logger LOG = LoggerFactory.getLogger(SqsCollectorHandler.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final CollectorHandler collector;
    private final BatchMessageHandler<SQSEvent, SQSBatchResponse> batch;

    public SqsCollectorHandler() { this(new CollectorHandler()); }
    SqsCollectorHandler(CollectorHandler collector) {
        this.collector = collector;
        batch = new BatchMessageHandlerBuilder().withSqsBatchHandler()
                .withFailureHandler((message, error) -> LOG.warn("sqs_message_failed", entries(Map.of(
                        "sqsMessageId", message.getMessageId(), "errorCategory", "PROCESSING_FAILED"))))
                .buildWithRawMessageHandler(this::processMessage);
    }
    @Override public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        return PowertoolsLogging.withLogging(context, () -> {
            if (event == null || event.getRecords() == null || event.getRecords().isEmpty()
                    || event.getRecords().stream().anyMatch(m -> m == null || m.getMessageId() == null || m.getMessageId().isBlank())) {
                LOG.warn("invalid_sqs_envelope");
                throw new IllegalArgumentException("Expected SQS records with message IDs");
            }
            LOG.info("sqs_batch_started", entries(Map.of("messageCount", event.getRecords().size())));
            var response = batch.processBatch(event, context);
            LOG.info("sqs_batch_completed", entries(Map.of("messageCount", event.getRecords().size(),
                    "failedCount", response.getBatchItemFailures().size())));
            return response;
        });
    }
    private void processMessage(SQSEvent.SQSMessage message) {
        String attempt = message.getAttributes() == null ? "unknown"
                : message.getAttributes().getOrDefault("ApproximateReceiveCount", "unknown");
        try (var scope = CloseableThreadContext.put("sqsMessageId", message.getMessageId()).put("receiveCount", attempt)) {
            LOG.info("sqs_message_started");
            Map<String, Object> job;
            try {
                if (message.getBody() == null || message.getBody().length() > 16384) throw new IllegalArgumentException();
                job = mapper.readValue(message.getBody(), new TypeReference<Map<String, Object>>() {});
                if (job == null) throw new IllegalArgumentException();
            } catch (Exception error) {
                LOG.warn("invalid_sqs_message_json");
                // Parser errors can contain raw input; do not propagate them to the batch library.
                throw new IllegalArgumentException("Invalid collection job JSON");
            }
            job.putIfAbsent("collectionId", "sqs-" + message.getMessageId());
            var result = collector.process(job);
            LOG.info("sqs_message_completed", entries(Map.of("outcome", result.get("outcome"),
                    "collectionId", result.get("collectionId"))));
        }
    }
}
