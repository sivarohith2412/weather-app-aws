package dev.weather.infra;

import java.util.Map;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.scheduler.CfnSchedule;
import software.amazon.awscdk.services.scheduler.CfnScheduleGroup;
import software.amazon.awscdk.services.lambda.Permission;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.sqs.*;
import software.constructs.Construct;

public final class WeatherStack extends Stack {
    public WeatherStack(Construct scope, String id, StackProps props, String artifact) {
        super(scope, id, props);
        // Reference the existing resources; this stack never creates or deletes them.
        var table = Table.fromTableArn(this, "ExistingReadings",
                "arn:aws:dynamodb:us-east-2:471084227270:table/weather-readings-dev");
        var secret = Secret.fromSecretCompleteArn(this, "ExistingWeatherKey",
                "arn:aws:secretsmanager:us-east-2:471084227270:secret:weather/dev/openweather-P4uYlV");
        var applicationFailures = Queue.Builder.create(this, "ApplicationFailures").queueName("weather-app-dlq-dev")
                .retentionPeriod(Duration.days(14)).encryption(QueueEncryption.SQS_MANAGED)
                .enforceSsl(true).removalPolicy(RemovalPolicy.RETAIN_ON_UPDATE_OR_DELETE).build();
        var application = new ApplicationFunction(this, "Application", artifact, table, secret, applicationFailures).function();
        var scheduleGroup = CfnScheduleGroup.Builder.create(this, "CollectionSchedules").name("weather-dev").build();
        var scheduleRole = Role.Builder.create(this, "ScheduleRole").roleName("weather-scheduler-dev-role")
                .assumedBy(new ServicePrincipal("scheduler.amazonaws.com").withConditions(Map.of(
                        "StringEquals", Map.of("aws:SourceAccount", getAccount()),
                        "ArnEquals", Map.of("aws:SourceArn", scheduleGroup.getAttrArn()))))
                .permissionsBoundary(ManagedPolicy.fromManagedPolicyArn(this, "SchedulerBoundary",
                        "arn:aws:iam::471084227270:policy/weather-scheduler-dev-boundary")).build();
        application.grantInvoke(scheduleRole);
        applicationFailures.grant(scheduleRole, "sqs:SendMessage");
        var state = CfnParameter.Builder.create(this, "CollectionScheduleState").type("String")
                .allowedValues(java.util.List.of("ENABLED", "DISABLED")).defaultValue("ENABLED")
                .description("Disable to pause scheduled provider requests without deleting collected data").build();
        var schedule = CfnSchedule.Builder.create(this, "FiveMinuteCollection").name("weather-collection-every-five-minutes")
                .groupName(scheduleGroup.getName()).scheduleExpression("rate(5 minutes)").state(state.getValueAsString())
                .flexibleTimeWindow(CfnSchedule.FlexibleTimeWindowProperty.builder().mode("OFF").build())
                .target(CfnSchedule.TargetProperty.builder().arn(application.getFunctionArn()).roleArn(scheduleRole.getRoleArn())
                        .input("{\"scheduledAt\":\"<aws.scheduler.scheduled-time>\"}")
                        .deadLetterConfig(CfnSchedule.DeadLetterConfigProperty.builder().arn(applicationFailures.getQueueArn()).build())
                        .retryPolicy(CfnSchedule.RetryPolicyProperty.builder().maximumRetryAttempts(2).maximumEventAgeInSeconds(3600).build())
                        .build()).build();
        schedule.addDependency(scheduleGroup);
        // Permission propagation must precede schedule creation, not only role creation.
        schedule.getNode().addDependency(scheduleRole);
        var hosting = new DashboardHosting(this, "Dashboard");
        var api = software.amazon.awscdk.services.apigatewayv2.CfnApi.Builder.create(this, "ReadApi")
                .name("weather-read-dev").protocolType("HTTP")
                .corsConfiguration(software.amazon.awscdk.services.apigatewayv2.CfnApi.CorsProperty.builder()
                        .allowOrigins(java.util.List.of("http://localhost:4200", "http://127.0.0.1:4200", hosting.url()))
                        .allowMethods(java.util.List.of("GET", "OPTIONS")).allowHeaders(java.util.List.of("content-type"))
                        .maxAge(300).build()).build();
        var integration = software.amazon.awscdk.services.apigatewayv2.CfnIntegration.Builder.create(this, "ReadIntegration")
                .apiId(api.getRef()).integrationType("AWS_PROXY").integrationUri(application.getFunctionArn())
                .payloadFormatVersion("2.0").timeoutInMillis(26000).build();
        int index = 0;
        for (String route : java.util.List.of("GET /cities", "GET /weather/latest", "GET /weather/history")) {
            software.amazon.awscdk.services.apigatewayv2.CfnRoute.Builder.create(this, "ReadRoute" + index++)
                    .apiId(api.getRef()).routeKey(route).authorizationType("NONE")
                    .target("integrations/" + integration.getRef()).build();
        }
        software.amazon.awscdk.services.apigatewayv2.CfnStage.Builder.create(this, "ReadStage")
                .apiId(api.getRef()).stageName("$default").autoDeploy(true)
                .defaultRouteSettings(software.amazon.awscdk.services.apigatewayv2.CfnStage.RouteSettingsProperty.builder()
                        .throttlingBurstLimit(10).throttlingRateLimit(5).build()).build();
        application.addPermission("ApiInvoke", Permission.builder().principal(new ServicePrincipal("apigateway.amazonaws.com"))
                .sourceAccount(getAccount()).sourceArn("arn:aws:execute-api:" + getRegion() + ":" + getAccount()
                        + ":" + api.getRef() + "/*/GET/*").build());
        CfnOutput.Builder.create(this, "ReadApiUrl").value(api.getAttrApiEndpoint()).build();
        CfnOutput.Builder.create(this, "ApplicationFunctionName").value(application.getFunctionName()).build();
        CfnOutput.Builder.create(this, "ApplicationLogGroup").value("/aws/lambda/weather-app-dev").build();
        CfnOutput.Builder.create(this, "ApplicationFailureQueueUrl").value(applicationFailures.getQueueUrl()).build();
        CfnOutput.Builder.create(this, "WebsiteUrl").value(hosting.url()).build();
        CfnOutput.Builder.create(this, "DashboardBucketName").value(hosting.bucketName()).build();
        CfnOutput.Builder.create(this, "DashboardDistributionId").value(hosting.distributionId()).build();
        Tags.of(this).add("Project", "weather");
        Tags.of(this).add("Environment", "dev");
        CfnOutput.Builder.create(this, "ScheduleName").value(schedule.getName()).build();
    }
}

