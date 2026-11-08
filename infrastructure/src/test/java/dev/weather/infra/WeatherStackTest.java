package dev.weather.infra;

import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.*;
import software.amazon.awscdk.assertions.*;

class WeatherStackTest {
    @Test void preservesExistingDataAndCreatesBoundedQueueWorker() {
        var app = new App();
        var stack = new WeatherStack(app, "WeatherDev", StackProps.builder()
                .env(Environment.builder().account(WeatherApp.ACCOUNT).region(WeatherApp.REGION).build()).build(), WeatherApp.artifact());
        var template = Template.fromStack(stack);
        template.resourceCountIs("AWS::DynamoDB::Table", 0);
        template.resourceCountIs("AWS::SecretsManager::Secret", 0);
        template.resourceCountIs("AWS::Lambda::Function", 1);
        template.resourceCountIs("AWS::SQS::Queue", 2);
        template.hasResourceProperties("AWS::Lambda::Function", Map.of("FunctionName", "weather-worker-dev", "Runtime", "java21",
                "Handler", "dev.weather.SqsCollectorHandler::handleRequest", "Timeout", 45, "MemorySize", 512));
        template.hasResourceProperties("AWS::SQS::Queue", Map.of("QueueName", "weather-collection-dev", "VisibilityTimeout", 270,
                "SqsManagedSseEnabled", true, "RedrivePolicy", Match.objectLike(Map.of("maxReceiveCount", 5))));
        template.hasResourceProperties("AWS::SQS::Queue", Map.of("QueueName", "weather-collection-dlq-dev", "MessageRetentionPeriod", 1209600));
        template.hasResourceProperties("AWS::Lambda::EventSourceMapping", Map.of("BatchSize", 1,
                "FunctionResponseTypes", java.util.List.of("ReportBatchItemFailures"), "ScalingConfig", Map.of("MaximumConcurrency", 2)));
        template.hasResourceProperties("AWS::Logs::LogGroup", Map.of("RetentionInDays", 14));
    }
}
