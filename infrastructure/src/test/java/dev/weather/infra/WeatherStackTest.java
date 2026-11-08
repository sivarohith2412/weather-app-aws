package dev.weather.infra;

import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.*;
import software.amazon.awscdk.assertions.*;

class WeatherStackTest {
    @Test void servesPrivateWebsiteFilesOnlyThroughSignedCloudFrontRequests() {
        var app = new App();
        var stack = new Stack(app, "HostingTest", StackProps.builder()
                .env(Environment.builder().account(WeatherApp.ACCOUNT).region(WeatherApp.REGION).build()).build());
        new DashboardHosting(stack, "Dashboard");
        var template = Template.fromStack(stack);
        template.hasResourceProperties("AWS::S3::Bucket", Map.of("BucketName", DashboardHosting.BUCKET_NAME,
                "PublicAccessBlockConfiguration", Map.of("BlockPublicAcls", true, "BlockPublicPolicy", true,
                        "IgnorePublicAcls", true, "RestrictPublicBuckets", true),
                "OwnershipControls", Map.of("Rules", java.util.List.of(Map.of("ObjectOwnership", "BucketOwnerEnforced")))));
        template.hasResourceProperties("AWS::CloudFront::OriginAccessControl", Map.of("OriginAccessControlConfig",
                Match.objectLike(Map.of("SigningBehavior", "always", "SigningProtocol", "sigv4", "OriginAccessControlOriginType", "s3"))));
        template.hasResourceProperties("AWS::CloudFront::Distribution", Map.of("DistributionConfig", Match.objectLike(Map.of(
                "DefaultRootObject", "index.html", "DefaultCacheBehavior", Match.objectLike(Map.of(
                        "ViewerProtocolPolicy", "redirect-to-https", "CachePolicyId", software.amazon.awscdk.services.cloudfront.CachePolicy.CACHING_DISABLED.getCachePolicyId()))))));
        template.hasResourceProperties("AWS::S3::BucketPolicy", Map.of("PolicyDocument", Match.objectLike(Map.of(
                "Statement", Match.arrayWith(java.util.List.of(Match.objectLike(Map.of("Effect", "Allow",
                        "Action", "s3:GetObject", "Principal", Map.of("Service", "cloudfront.amazonaws.com"),
                        "Condition", Map.of("StringEquals", Map.of("AWS:SourceArn", Match.anyValue()))))))))));
    }
    @Test void preservesExistingDataAndCreatesBoundedQueueWorker() {
        var app = new App();
        var stack = new WeatherStack(app, "WeatherDev", StackProps.builder()
                .env(Environment.builder().account(WeatherApp.ACCOUNT).region(WeatherApp.REGION).build()).build(), WeatherApp.artifact());
        var template = Template.fromStack(stack);
        template.resourceCountIs("AWS::DynamoDB::Table", 0);
        template.resourceCountIs("AWS::SecretsManager::Secret", 0);
        template.resourceCountIs("AWS::Lambda::Function", 3);
        template.resourceCountIs("AWS::SQS::Queue", 3);
        template.hasResourceProperties("AWS::IAM::Role", Map.of("RoleName", "weather-worker-dev-role",
                "PermissionsBoundary", "arn:aws:iam::471084227270:policy/weather-worker-dev-boundary"));
        template.hasResourceProperties("AWS::Lambda::Function", Map.of("FunctionName", "weather-worker-dev", "Runtime", "java21",
                "Handler", "dev.weather.SqsCollectorHandler::handleRequest", "Timeout", 45, "MemorySize", 512));
        template.hasResourceProperties("AWS::SQS::Queue", Map.of("QueueName", "weather-collection-dev", "VisibilityTimeout", 270,
                "SqsManagedSseEnabled", true, "RedrivePolicy", Match.objectLike(Map.of("maxReceiveCount", 5))));
        template.hasResourceProperties("AWS::SQS::Queue", Map.of("QueueName", "weather-collection-dlq-dev", "MessageRetentionPeriod", 1209600));
        template.hasResourceProperties("AWS::Lambda::EventSourceMapping", Map.of("BatchSize", 1,
                "FunctionResponseTypes", java.util.List.of("ReportBatchItemFailures"), "ScalingConfig", Map.of("MaximumConcurrency", 2)));
        template.hasResourceProperties("AWS::Logs::LogGroup", Map.of("RetentionInDays", 14));
        template.hasResourceProperties("AWS::Lambda::Function", Map.of("FunctionName", "weather-dispatcher-dev",
                "Handler", "dev.weather.DispatcherHandler::handleRequest", "Timeout", 30,
                "DeadLetterConfig", Match.anyValue()));
        template.hasResourceProperties("AWS::Scheduler::Schedule", Map.of("ScheduleExpression", "rate(5 minutes)",
                "FlexibleTimeWindow", Map.of("Mode", "OFF"), "Target", Match.objectLike(Map.of(
                        "RetryPolicy", Map.of("MaximumEventAgeInSeconds", 3600, "MaximumRetryAttempts", 2),
                        "DeadLetterConfig", Match.anyValue(), "Input", "{\"scheduledAt\":\"<aws.scheduler.scheduled-time>\"}"))));
        template.hasResourceProperties("AWS::IAM::Role", Map.of("RoleName", "weather-dispatcher-dev-role",
                "PermissionsBoundary", "arn:aws:iam::471084227270:policy/weather-dispatcher-dev-boundary"));
        template.hasResourceProperties("AWS::IAM::Role", Map.of("RoleName", "weather-scheduler-dev-role",
                "PermissionsBoundary", "arn:aws:iam::471084227270:policy/weather-scheduler-dev-boundary"));
        template.hasResourceProperties("AWS::IAM::Policy", Map.of("PolicyDocument", Match.objectLike(Map.of(
                "Statement", Match.arrayWith(java.util.List.of(Match.objectLike(Map.of(
                        "Effect", "Allow", "Action", Match.arrayWith(java.util.List.of("logs:CreateLogStream", "logs:PutLogEvents"))))))))));
    }
}

