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
    @Test void preservesDataAndRoutesBothTriggersToExactlyOneFunction() {
        var app = new App();
        var stack = new WeatherStack(app, "WeatherDev", StackProps.builder()
                .env(Environment.builder().account(WeatherApp.ACCOUNT).region(WeatherApp.REGION).build()).build(), WeatherApp.artifact());
        var template = Template.fromStack(stack);
        template.resourceCountIs("AWS::DynamoDB::Table", 0);
        template.resourceCountIs("AWS::SecretsManager::Secret", 0);
        template.resourceCountIs("AWS::Lambda::Function", 1);
        template.resourceCountIs("AWS::SQS::Queue", 1);
        template.resourceCountIs("AWS::Lambda::EventSourceMapping", 0);
        template.hasResourceProperties("AWS::Lambda::Function", Map.of("FunctionName", "weather-app-dev", "Runtime", "java21",
                "Handler", "dev.weather.WeatherAppHandler::handleRequest", "Timeout", 180, "MemorySize", 512));
        template.hasResourceProperties("AWS::IAM::Role", Map.of("RoleName", "weather-app-dev-role",
                "PermissionsBoundary", "arn:aws:iam::471084227270:policy/weather-app-dev-boundary"));
        var functionId = template.findResources("AWS::Lambda::Function").keySet().iterator().next();
        var functionArn = Map.of("Fn::GetAtt", java.util.List.of(functionId, "Arn"));
        template.hasResourceProperties("AWS::Scheduler::Schedule", Map.of("ScheduleExpression", "rate(5 minutes)",
                "Target", Match.objectLike(Map.of("Arn", functionArn))));
        template.hasResourceProperties("AWS::ApiGatewayV2::Integration", Map.of("IntegrationUri", functionArn));
        template.hasResourceProperties("AWS::SQS::Queue", Map.of("QueueName", "weather-app-dlq-dev", "MessageRetentionPeriod", 1209600));
    }
}
