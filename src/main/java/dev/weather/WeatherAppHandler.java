package dev.weather;

import com.amazonaws.services.lambda.runtime.*;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.apache.logging.log4j.CloseableThreadContext;
import org.slf4j.LoggerFactory;
import static software.amazon.lambda.powertools.logging.argument.StructuredArguments.entries;
import software.amazon.lambda.powertools.logging.PowertoolsLogging;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/** One deployed function: HTTP reads and scheduled collection use separate code paths. */
public final class WeatherAppHandler implements RequestHandler<Map<String, Object>, Object> {
    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Supplier<CollectionService> collector;
    private final ReadApiHandler reader;
    public WeatherAppHandler() { this(() -> Resources.COLLECTOR, new ReadApiHandler()); }
    WeatherAppHandler(Supplier<CollectionService> collector, ReadApiHandler reader) {
        this.collector = collector; this.reader = reader;
    }
    @Override public Object handleRequest(Map<String, Object> event, Context context) {
        return PowertoolsLogging.withLogging(context, () -> process(event));
    }
    Object process(Map<String, Object> event) {
        // API Gateway owns these top-level fields. A query/body cannot select collection mode.
        if (event != null && event.containsKey("requestContext")) {
            try { return reader.process(JSON.convertValue(event, APIGatewayV2HTTPEvent.class)); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid HTTP event"); }
        }
        final Instant scheduled;
        try {
            if (event == null || !(event.get("scheduledAt") instanceof String value)) throw new IllegalArgumentException();
            scheduled = Instant.parse(value);
        } catch (Exception e) { throw new IllegalArgumentException("Expected scheduledAt as an ISO timestamp"); }
        String collectionId = "dev-" + scheduled;
        long started = System.nanoTime();
        var results = new ArrayList<Map<String, String>>();
        var failed = new ArrayList<String>();
        try (var scope = CloseableThreadContext.put("collectionId", collectionId).put("environment", "dev")) {
            var log = LoggerFactory.getLogger(WeatherAppHandler.class);
            log.info("collection_run_started", entries(Map.of("cityCount", CityCatalog.all().size())));
            CollectionService service;
            try { service = collector.get(); }
            catch (RuntimeException | ExceptionInInitializerError e) {
                log.error("collection_initialization_failed");
                throw new IllegalStateException("Weather collector could not be initialized");
            }
            // Complete the remaining cities after an individual failure; async retry repeats the run.
            for (var city : CityCatalog.all()) {
                try {
                    var result = service.collect(city, collectionId);
                    results.add(Map.of("cityId", city.id(), "outcome", result.outcome(), "observedAt", result.reading().observedAt()));
                } catch (RuntimeException e) { failed.add(city.id()); }
            }
            var fields = Map.<String, Object>of("cityCount", CityCatalog.all().size(), "successfulCityCount", results.size(),
                    "failedCityIds", failed, "durationMs", CollectionService.elapsed(started));
            if (!failed.isEmpty()) {
                log.error("collection_run_failed", entries(fields));
                throw new IllegalStateException("Weather collection failed for " + failed.size() + " cities; see correlated logs");
            }
            log.info("collection_run_completed", entries(fields));
            return Map.of("collectionId", collectionId, "scheduledAt", scheduled.toString(), "cityCount", results.size(),
                    "successfulCityCount", results.size(), "results", results);
        }
    }
    private static final class Resources {
        static final CollectionService COLLECTOR = create();
        private static CollectionService create() {
            var region = Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-2"));
            var db = DynamoDbClient.builder().region(region)
                    .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(5))).build();
            var secrets = SecretsManagerClient.builder().region(region)
                    .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(8)).apiCallAttemptTimeout(Duration.ofSeconds(3))).build();
            String secretId = System.getenv("OPENWEATHER_SECRET_ARN");
            if (secretId == null || secretId.isBlank()) throw new IllegalStateException("Secret ARN is required");
            var repository = new DynamoWeatherRepository(db, System.getenv().getOrDefault("WEATHER_TABLE_NAME", "weather-readings-dev"));
            return new CollectionService(new WeatherClient()::fetch, repository::save, new SecretApiKey(secrets, secretId));
        }
    }
}
