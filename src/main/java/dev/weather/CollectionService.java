package dev.weather;

import java.util.Map;
import java.util.HashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.logging.log4j.CloseableThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import static software.amazon.lambda.powertools.logging.argument.StructuredArguments.entries;

/** Shared by the local CLI and Lambda, with credential-safe diagnostics. */
public final class CollectionService {
    private static final Logger LOG = LoggerFactory.getLogger(CollectionService.class);
    private final BiFunction<City, String, Reading> fetch;
    private final Function<Reading, DynamoWeatherRepository.SaveResult> save;
    private final Supplier<String> key;

    public CollectionService(BiFunction<City, String, Reading> fetch,
            Function<Reading, DynamoWeatherRepository.SaveResult> save, Supplier<String> key) {
        this.fetch = fetch; this.save = save; this.key = key;
    }
    public record Result(Reading reading, String outcome, String collectionId) {}

    public Result collect(City city, String collectionId) {
        long started = System.nanoTime();
        String stage = "secret";
        try (var ignored = CloseableThreadContext.put("cityId", city.id()).put("collectionId", collectionId)
                .put("environment", System.getenv().getOrDefault("ENVIRONMENT", "dev"))) {
            LOG.info("collection_started", entries(Map.of("provider", "openweather")));
            try {
                long step = System.nanoTime();
                String apiKey = key.get();
                LOG.info("credential_ready", entries(Map.of("durationMs", elapsed(step))));
                stage = "provider";
                step = System.nanoTime();
                Reading reading = fetch.apply(city, apiKey);
                LOG.info("weather_received", entries(Map.of("durationMs", elapsed(step), "observedAt", reading.observedAt(),
                        "collectedAt", reading.collectedAt(), "temperatureC", reading.temperatureC(),
                        "humidityPercent", reading.humidityPercent(), "windSpeedMps", reading.windSpeedMps(),
                        "conditionCode", reading.conditionCode())));
                stage = "dynamodb";
                step = System.nanoTime();
                String outcome = save == null ? "FETCHED" : save.apply(reading).name();
                LOG.info("collection_completed", entries(Map.of("outcome", outcome, "saveDurationMs", elapsed(step),
                        "durationMs", elapsed(started), "observedAt", reading.observedAt())));
                return new Result(reading, outcome, collectionId);
            } catch (RuntimeException error) {
                String category = category(error);
                // Never pass the throwable or its message: either may carry secrets or URLs.
                var fields = new HashMap<String, Object>();
                fields.put("stage", stage);
                fields.put("errorCategory", category);
                fields.put("errorType", error.getClass().getSimpleName());
                fields.put("durationMs", elapsed(started));
                if (error instanceof AwsServiceException aws) {
                    fields.put("httpStatus", aws.statusCode());
                    if (aws.requestId() != null) fields.put("awsServiceRequestId", aws.requestId());
                }
                LOG.error("collection_failed", entries(fields));
                throw new CollectionFailure(category);
            }
        }
    }
    static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    static String category(RuntimeException e) {
        if (e instanceof WeatherException weather) return weather.category().name();
        if (e instanceof AwsServiceException aws) return "AWS_HTTP_" + aws.statusCode();
        if (e instanceof SdkClientException) return "AWS_CONNECTION";
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) return "CONFIGURATION";
        return "UNEXPECTED";
    }
    public static final class CollectionFailure extends RuntimeException {
        CollectionFailure(String category) { super("Weather collection failed: " + category); }
    }
}
