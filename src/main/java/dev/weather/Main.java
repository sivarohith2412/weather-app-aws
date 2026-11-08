package dev.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.apache.logging.log4j.CloseableThreadContext;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

public final class Main {
    public static void main(String[] args) {
        String collectionId = "local-" + UUID.randomUUID();
        try (var scope = CloseableThreadContext.put("collectionId", collectionId).put("environment", "dev")) {
            boolean save = Arrays.asList(args).contains("--save");
            boolean check = Arrays.asList(args).contains("--check-db");
            if (check) {
                try (var credentials = DefaultCredentialsProvider.builder().build(); var db = database(credentials)) {
                    var table = db.describeTable(DescribeTableRequest.builder().tableName(tableName()).build()).table();
                    System.out.println("DynamoDB connection OK: " + table.tableName() + " (" + table.tableStatusAsString() + ")");
                    LoggerFactory.getLogger(Main.class).info("database_connection_verified");
                }
                return;
            }
            var boston = new City("us-boston", "Boston", "US", 42.3601, -71.0589);
            CollectionService.Result result;
            if (save) {
                try (var credentials = DefaultCredentialsProvider.builder().build(); var db = database(credentials)) {
                    result = new CollectionService(new WeatherClient()::fetch,
                            new DynamoWeatherRepository(db, tableName())::save,
                            () -> System.getenv("OPENWEATHER_API_KEY")).collect(boston, collectionId);
                }
            } else {
                result = new CollectionService(new WeatherClient()::fetch, null,
                        () -> System.getenv("OPENWEATHER_API_KEY")).collect(boston, collectionId);
            }
            System.out.println(new ObjectMapper().writeValueAsString(result.reading()));
            if (save) System.out.println("DynamoDB: " + result.outcome());
        } catch (CollectionService.CollectionFailure e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (DynamoDbException e) {
            System.err.println("DynamoDB request failed: HTTP " + e.statusCode()
                    + " (request ID: " + e.requestId() + ")");
            System.exit(1);
        } catch (SdkClientException e) {
            System.err.println("AWS connection failed. Check your AWS_PROFILE, region, and active login session.");
            System.exit(1);
        } catch (WeatherException | IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Unable to output weather reading");
            System.exit(1);
        }
    }
    private static String tableName() {
        return System.getenv().getOrDefault("WEATHER_TABLE_NAME", "weather-readings-dev");
    }
    private static DynamoDbClient database(DefaultCredentialsProvider credentials) {
        return DynamoDbClient.builder()
                .credentialsProvider(credentials)
                .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-2")))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(20))
                        .apiCallAttemptTimeout(Duration.ofSeconds(8)))
                .build();
    }
}
