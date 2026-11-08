package dev.weather;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.lambda.powertools.logging.PowertoolsLogging;

/** Direct invocation entry point; SQS reuses process() with its own logging scope. */
public final class CollectorHandler implements RequestHandler<Map<String, Object>, Map<String, String>> {
    private final Supplier<CollectionService> service;
    public CollectorHandler() { this(() -> Resources.SERVICE); }
    CollectorHandler(Supplier<CollectionService> service) { this.service = service; }

    @Override public Map<String, String> handleRequest(Map<String, Object> event, Context context) {
        return PowertoolsLogging.withLogging(context, () -> process(event));
    }
    Map<String, String> process(Map<String, Object> event) {
            // Only allow the configured city; never use caller-supplied coordinates or credentials.
            if (event == null || CityCatalog.find(event.get("cityId")).isEmpty()
                    || !(event.get("schemaVersion") instanceof Number n) || n.doubleValue() != 1) {
                LoggerFactory.getLogger(CollectorHandler.class).warn("invalid_collection_job");
                throw new IllegalArgumentException("Expected schemaVersion 1 and a configured cityId");
            }
            Object supplied = event.get("collectionId");
            String collectionId = supplied == null ? "dev-" + UUID.randomUUID() : supplied.toString();
            if (!collectionId.matches("[A-Za-z0-9_.:Z-]{1,100}")) {
                LoggerFactory.getLogger(CollectorHandler.class).warn("invalid_collection_id");
                throw new IllegalArgumentException("Invalid collectionId format");
            }
            City city = CityCatalog.find(event.get("cityId")).orElseThrow();
            try {
                var result = service.get().collect(city, collectionId);
                return Map.of("cityId", city.id(), "collectionId", collectionId,
                        "outcome", result.outcome(), "observedAt", result.reading().observedAt());
            } catch (CollectionService.CollectionFailure e) { throw e; }
            catch (RuntimeException | ExceptionInInitializerError | NoClassDefFoundError e) {
                // Static initialization / credential failures must not escape with raw secret payloads.
                LoggerFactory.getLogger(CollectorHandler.class).error("collector_initialization_failed");
                throw new IllegalStateException("Collector configuration could not be initialized");
            }
    }
    private static final class Resources {
        static final CollectionService SERVICE = create();
        private static CollectionService create() {
            Region region = Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-2"));
            var db = DynamoDbClient.builder().region(region)
                    .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(5))).build();
            var secrets = SecretsManagerClient.builder().region(region)
                    .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(8)).apiCallAttemptTimeout(Duration.ofSeconds(3))).build();
            String secretId = System.getenv("OPENWEATHER_SECRET_ARN");
            if (secretId == null || secretId.isBlank()) throw new IllegalStateException("OPENWEATHER_SECRET_ARN is required");
            var repository = new DynamoWeatherRepository(db, System.getenv().getOrDefault("WEATHER_TABLE_NAME", "weather-readings-dev"));
            return new CollectionService(new WeatherClient()::fetch, repository::save, new SecretApiKey(secrets, secretId));
        }
    }
}
