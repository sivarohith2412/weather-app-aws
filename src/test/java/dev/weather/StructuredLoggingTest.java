package dev.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.layout.template.json.JsonTemplateLayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StructuredLoggingTest {
    @Test void emitsParseableCorrelatedJsonWithoutSecretsAndClearsContext() throws Exception {
        var context = (LoggerContext) LogManager.getContext(false);
        var layout = JsonTemplateLayout.newBuilder().setConfiguration(context.getConfiguration())
                .setEventTemplateUri("classpath:LambdaJsonLayout.json").build();
        var lines = new ArrayList<String>();
        var capture = new AbstractAppender("capture", null, layout, false, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) { lines.add(layout.toSerializable(event)); }
        };
        capture.start();
        var logger = context.getLogger(CollectionService.class.getName());
        logger.addAppender(capture);
        try {
            var service = new CollectionService((city,key) -> TestFixtures.READING,
                    r -> { throw new IllegalStateException("private-api-key https://example.test?appid=private-api-key"); },
                    () -> "private-api-key");
            assertThrows(CollectionService.CollectionFailure.class, () -> software.amazon.lambda.powertools.logging.PowertoolsLogging.withLogging(
                    TestFixtures.context(), () -> service.collect(CityCatalog.find("us-boston").orElseThrow(), "dev-logging-test")));
            assertFalse(lines.isEmpty());
            var mapper = new ObjectMapper();
            boolean failed = false;
            for (String line : lines) {
                assertFalse(line.contains("private-api-key"));
                assertFalse(line.contains("appid="));
                var json = mapper.readTree(line);
                assertEquals("us-boston", json.path("cityId").asText());
                assertEquals("dev-logging-test", json.path("collectionId").asText());
                assertEquals("test-request-id", json.path("function_request_id").asText());
                if (json.path("message").asText().equals("collection_failed")) {
                    failed = true;
                    assertEquals("dynamodb", json.path("stage").asText());
                    assertEquals("CONFIGURATION", json.path("errorCategory").asText());
                }
            }
            assertTrue(failed);
            assertFalse(org.apache.logging.log4j.ThreadContext.containsKey("cityId"));
            assertFalse(org.apache.logging.log4j.ThreadContext.containsKey("collectionId"));
        } finally { logger.removeAppender(capture); capture.stop(); }
    }
}
