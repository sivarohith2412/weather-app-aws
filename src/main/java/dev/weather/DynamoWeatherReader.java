package dev.weather;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Bounded partition queries only; this client cannot write or scan the table. */
public final class DynamoWeatherReader implements WeatherReadStore {
    private final DynamoDbClient db;
    private final String table;
    public DynamoWeatherReader(DynamoDbClient db, String table) { this.db = db; this.table = table; }
    @Override public Page query(String cityId, Instant from, Instant to, int limit) {
        var result = db.query(QueryRequest.builder().tableName(table).consistentRead(true)
                .keyConditionExpression("#city = :city AND #time BETWEEN :from AND :to")
                .expressionAttributeNames(Map.of("#city", "cityId", "#time", "observedAt"))
                .expressionAttributeValues(Map.of(":city", text(cityId), ":from", text(from.toString()), ":to", text(to.toString())))
                .scanIndexForward(false).limit(limit).build());
        var readings = result.items().stream().map(DynamoWeatherReader::reading).toList();
        return new Page(readings, result.hasLastEvaluatedKey() && !result.lastEvaluatedKey().isEmpty());
    }
    static Reading reading(Map<String, AttributeValue> i) {
        return new Reading(i.get("cityId").s(), i.get("cityName").s(), i.get("country").s(),
                decimal(i, "temperatureC").doubleValue(), decimal(i, "humidityPercent").intValueExact(),
                decimal(i, "windSpeedMps").doubleValue(), decimal(i, "conditionCode").intValueExact(),
                i.get("condition").s(), i.get("observedAt").s(), i.get("collectedAt").s(), i.get("provider").s());
    }
    private static BigDecimal decimal(Map<String, AttributeValue> i, String key) { return new BigDecimal(i.get(key).n()); }
    private static AttributeValue text(String s) { return AttributeValue.builder().s(s).build(); }
}
