package dev.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/** Expects a JSON secret with an OPENWEATHER_API_KEY string; caches for five minutes. */
final class SecretApiKey implements Supplier<String> {
    private final Supplier<String> load;
    private final Clock clock;
    private String cached;
    private Instant refreshAt = Instant.MIN;
    SecretApiKey(SecretsManagerClient client, String secretId) {
        this(() -> client.getSecretValue(GetSecretValueRequest.builder().secretId(secretId).build()).secretString(), Clock.systemUTC());
    }
    SecretApiKey(Supplier<String> load, Clock clock) { this.load = load; this.clock = clock; }
    @Override public synchronized String get() {
        if (cached != null && clock.instant().isBefore(refreshAt)) {
            LoggerFactory.getLogger(SecretApiKey.class).debug("secret_cache_hit");
            return cached;
        }
        String payload = load.get();
        String next;
        try {
            var node = new ObjectMapper().readTree(payload).path("OPENWEATHER_API_KEY");
            if (!node.isTextual() || node.asText().isBlank()) throw new IllegalArgumentException();
            next = node.asText().trim();
        } catch (Exception e) { throw new IllegalStateException("Secret must contain an OPENWEATHER_API_KEY string"); }
        cached = next;
        refreshAt = clock.instant().plus(Duration.ofMinutes(5));
        LoggerFactory.getLogger(SecretApiKey.class).info("secret_cache_refreshed");
        return cached;
    }
}
