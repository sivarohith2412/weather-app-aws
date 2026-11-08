package dev.weather;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.LoggerFactory;
import static software.amazon.lambda.powertools.logging.argument.StructuredArguments.entries;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/** Stores one item per city/provider observation; duplicate deliveries are successful no-ops. */
public final class DynamoWeatherRepository {
    public enum SaveResult { SAVED, ALREADY_EXISTS }
    private final DynamoDbClient client;
    private final String table;

    public DynamoWeatherRepository(DynamoDbClient client, String table) {
        if (table == null || table.isBlank()) throw new IllegalArgumentException("Table name is required");
        this.client = client;
        this.table = table;
    }

    public SaveResult save(Reading reading) {
        long started = System.nanoTime();
        var request = PutItemRequest.builder().tableName(table).item(toItem(reading))
                .conditionExpression("attribute_not_exists(#city) AND attribute_not_exists(#observed)")
                .expressionAttributeNames(Map.of("#city", "cityId", "#observed", "observedAt"))
                .build();
        try {
            var response = client.putItem(request);
            var fields = new HashMap<String, Object>();
            fields.put("tableName", table);
            fields.put("durationMs", CollectionService.elapsed(started));
            if (response.responseMetadata() != null && response.responseMetadata().requestId() != null)
                fields.put("dynamoRequestId", response.responseMetadata().requestId());
            LoggerFactory.getLogger(DynamoWeatherRepository.class).info("reading_saved", entries(fields));
            return SaveResult.SAVED;
        } catch (ConditionalCheckFailedException e) {
            LoggerFactory.getLogger(DynamoWeatherRepository.class).info("duplicate_reading_skipped", entries(Map.of(
                    "tableName", table, "durationMs", CollectionService.elapsed(started))));
            return SaveResult.ALREADY_EXISTS;
        }
    }

    static Map<String, AttributeValue> toItem(Reading r) {
        var item = new HashMap<String, AttributeValue>();
        item.put("cityId", text(r.cityId()));
        // Fixed-width UTC keys ensure lexical order matches chronological order.
        var observed = Instant.parse(r.observedAt());
        if (observed.getNano() != 0) throw new IllegalArgumentException("Observation must use whole seconds");
        item.put("observedAt", text(observed.toString()));
        item.put("cityName", text(r.cityName()));
        item.put("country", text(r.country()));
        item.put("temperatureC", number(r.temperatureC()));
        item.put("humidityPercent", number(r.humidityPercent()));
        item.put("windSpeedMps", number(r.windSpeedMps()));
        item.put("conditionCode", number(r.conditionCode()));
        item.put("condition", text(r.condition()));
        item.put("collectedAt", text(r.collectedAt()));
        item.put("provider", text(r.provider()));
        item.put("expiresAt", AttributeValue.builder().n(Long.toString(observed.plus(Duration.ofDays(30)).getEpochSecond())).build());
        return item;
    }
    private static AttributeValue text(String value) { return AttributeValue.builder().s(value).build(); }
    private static AttributeValue number(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Reading contains an invalid number");
        return AttributeValue.builder().n(Double.toString(value)).build();
    }
}
