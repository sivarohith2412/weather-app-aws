package dev.weather.infra;

import java.util.Map;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.scheduler.CfnSchedule;
import software.amazon.awscdk.services.scheduler.CfnScheduleGroup;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.lambda.eventsources.SqsEventSource;
import software.amazon.awscdk.services.logs.*;
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
        var dlq = Queue.Builder.create(this, "FailedCollections")
                .queueName("weather-collection-dlq-dev").retentionPeriod(Duration.days(14))
                .encryption(QueueEncryption.SQS_MANAGED).enforceSsl(true).removalPolicy(RemovalPolicy.RETAIN).build();
        var queue = Queue.Builder.create(this, "CollectionJobs").queueName("weather-collection-dev")
                .visibilityTimeout(Duration.seconds(270)).receiveMessageWaitTime(Duration.seconds(20))
                .retentionPeriod(Duration.days(4)).encryption(QueueEncryption.SQS_MANAGED).enforceSsl(true)
                .deadLetterQueue(DeadLetterQueue.builder().queue(dlq).maxReceiveCount(5).build())
                .removalPolicy(RemovalPolicy.RETAIN).build();
        var logs = LogGroup.Builder.create(this, "WorkerLogs").logGroupName("/aws/lambda/weather-worker-dev")
                .retention(RetentionDays.TWO_WEEKS).removalPolicy(RemovalPolicy.RETAIN).build();
        var role = Role.Builder.create(this, "WorkerRole").roleName("weather-worker-dev-role")
                .permissionsBoundary(ManagedPolicy.fromManagedPolicyArn(this, "WorkerBoundary",
                        "arn:aws:iam::471084227270:policy/weather-worker-dev-boundary"))
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com")).build();
        var worker = Function.Builder.create(this, "Worker").functionName("weather-worker-dev")
                .runtime(software.amazon.awscdk.services.lambda.Runtime.JAVA_21).architecture(Architecture.X86_64)
                .handler("dev.weather.SqsCollectorHandler::handleRequest").code(Code.fromAsset(artifact))
                .memorySize(512).timeout(Duration.seconds(45)).role(role).logGroup(logs)
                .loggingFormat(LoggingFormat.JSON)
                .environment(Map.of("WEATHER_TABLE_NAME", table.getTableName(), "OPENWEATHER_SECRET_ARN", secret.getSecretArn(),
                        "ENVIRONMENT", "dev", "POWERTOOLS_SERVICE_NAME", "weather-worker", "POWERTOOLS_LOG_LEVEL", "INFO"))
                .build();
        // The worker only inserts observations; it does not query, scan, or delete the table.
        table.grant(worker, "dynamodb:PutItem");
        secret.grantRead(worker);
        logs.grantWrite(role);
        worker.addEventSource(SqsEventSource.Builder.create(queue).batchSize(1)
                .reportBatchItemFailures(true).maxConcurrency(2).build());
        var dispatchDlq = Queue.Builder.create(this, "DispatchFailures").queueName("weather-dispatch-dlq-dev")
                .retentionPeriod(Duration.days(14)).encryption(QueueEncryption.SQS_MANAGED)
                .enforceSsl(true).removalPolicy(RemovalPolicy.RETAIN).build();
        var dispatchLogs = LogGroup.Builder.create(this, "DispatcherLogs").logGroupName("/aws/lambda/weather-dispatcher-dev")
                .retention(RetentionDays.TWO_WEEKS).removalPolicy(RemovalPolicy.RETAIN).build();
        var dispatchRole = Role.Builder.create(this, "DispatcherRole").roleName("weather-dispatcher-dev-role")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .permissionsBoundary(ManagedPolicy.fromManagedPolicyArn(this, "DispatcherBoundary",
                        "arn:aws:iam::471084227270:policy/weather-dispatcher-dev-boundary")).build();
        var dispatcher = Function.Builder.create(this, "Dispatcher").functionName("weather-dispatcher-dev")
                .runtime(software.amazon.awscdk.services.lambda.Runtime.JAVA_21).architecture(Architecture.X86_64)
                .handler("dev.weather.DispatcherHandler::handleRequest").code(Code.fromAsset(artifact))
                .memorySize(512).timeout(Duration.seconds(30)).role(dispatchRole).logGroup(dispatchLogs)
                .loggingFormat(LoggingFormat.JSON).deadLetterQueue(dispatchDlq)
                .retryAttempts(2).maxEventAge(Duration.hours(1))
                .environment(Map.of("COLLECTION_QUEUE_URL", queue.getQueueUrl(), "ENVIRONMENT", "dev",
                        "POWERTOOLS_SERVICE_NAME", "weather-dispatcher", "POWERTOOLS_LOG_LEVEL", "INFO")).build();
        queue.grantSendMessages(dispatcher);
        dispatchLogs.grantWrite(dispatchRole);
        var scheduleGroup = CfnScheduleGroup.Builder.create(this, "CollectionSchedules").name("weather-dev").build();
        var scheduleRole = Role.Builder.create(this, "ScheduleRole").roleName("weather-scheduler-dev-role")
                .assumedBy(new ServicePrincipal("scheduler.amazonaws.com").withConditions(Map.of(
                        "StringEquals", Map.of("aws:SourceAccount", getAccount()),
                        "ArnEquals", Map.of("aws:SourceArn", scheduleGroup.getAttrArn()))))
                .permissionsBoundary(ManagedPolicy.fromManagedPolicyArn(this, "SchedulerBoundary",
                        "arn:aws:iam::471084227270:policy/weather-scheduler-dev-boundary")).build();
        dispatcher.grantInvoke(scheduleRole);
        dispatchDlq.grant(scheduleRole, "sqs:SendMessage");
        var state = CfnParameter.Builder.create(this, "CollectionScheduleState").type("String")
                .allowedValues(java.util.List.of("ENABLED", "DISABLED")).defaultValue("ENABLED")
                .description("Disable to pause scheduled provider requests without deleting collected data").build();
        var schedule = CfnSchedule.Builder.create(this, "FiveMinuteCollection").name("weather-collection-every-five-minutes")
                .groupName(scheduleGroup.getName()).scheduleExpression("rate(5 minutes)").state(state.getValueAsString())
                .flexibleTimeWindow(CfnSchedule.FlexibleTimeWindowProperty.builder().mode("OFF").build())
                .target(CfnSchedule.TargetProperty.builder().arn(dispatcher.getFunctionArn()).roleArn(scheduleRole.getRoleArn())
                        .input("{\"scheduledAt\":\"<aws.scheduler.scheduled-time>\"}")
                        .deadLetterConfig(CfnSchedule.DeadLetterConfigProperty.builder().arn(dispatchDlq.getQueueArn()).build())
                        .retryPolicy(CfnSchedule.RetryPolicyProperty.builder().maximumRetryAttempts(2).maximumEventAgeInSeconds(3600).build())
                        .build()).build();
        schedule.addDependency(scheduleGroup);
        // Permission propagation must precede schedule creation, not only role creation.
        schedule.getNode().addDependency(scheduleRole);
        var readLogs = LogGroup.Builder.create(this, "ReaderLogs").logGroupName("/aws/lambda/weather-reader-dev")
                .retention(RetentionDays.TWO_WEEKS).removalPolicy(RemovalPolicy.RETAIN).build();
        var readRole = Role.Builder.create(this, "ReaderRole").roleName("weather-reader-dev-role")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .permissionsBoundary(ManagedPolicy.fromManagedPolicyArn(this, "ReaderBoundary",
                        "arn:aws:iam::471084227270:policy/weather-reader-dev-boundary")).build();
        var reader = Function.Builder.create(this, "Reader").functionName("weather-reader-dev")
                .runtime(software.amazon.awscdk.services.lambda.Runtime.JAVA_21).architecture(Architecture.X86_64)
                .handler("dev.weather.ReadApiHandler::handleRequest").code(Code.fromAsset(artifact))
                .memorySize(512).timeout(Duration.seconds(25)).role(readRole).logGroup(readLogs).loggingFormat(LoggingFormat.JSON)
                .environment(Map.of("WEATHER_TABLE_NAME", table.getTableName(), "POWERTOOLS_SERVICE_NAME", "weather-api",
                        "POWERTOOLS_LOG_LEVEL", "INFO")).build();
        table.grant(reader, "dynamodb:Query");
        readLogs.grantWrite(readRole);
        var hosting = new DashboardHosting(this, "Dashboard");
        var api = software.amazon.awscdk.services.apigatewayv2.CfnApi.Builder.create(this, "ReadApi")
                .name("weather-read-dev").protocolType("HTTP")
                .corsConfiguration(software.amazon.awscdk.services.apigatewayv2.CfnApi.CorsProperty.builder()
                        .allowOrigins(java.util.List.of("http://localhost:4200", "http://127.0.0.1:4200", hosting.url()))
                        .allowMethods(java.util.List.of("GET", "OPTIONS")).allowHeaders(java.util.List.of("content-type"))
                        .maxAge(300).build()).build();
        var integration = software.amazon.awscdk.services.apigatewayv2.CfnIntegration.Builder.create(this, "ReadIntegration")
                .apiId(api.getRef()).integrationType("AWS_PROXY").integrationUri(reader.getFunctionArn())
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
        reader.addPermission("ApiInvoke", Permission.builder().principal(new ServicePrincipal("apigateway.amazonaws.com"))
                .sourceAccount(getAccount()).sourceArn("arn:aws:execute-api:" + getRegion() + ":" + getAccount()
                        + ":" + api.getRef() + "/*/GET/*").build());
        CfnOutput.Builder.create(this, "ReadApiUrl").value(api.getAttrApiEndpoint()).build();
        CfnOutput.Builder.create(this, "WebsiteUrl").value(hosting.url()).build();
        CfnOutput.Builder.create(this, "DashboardBucketName").value(hosting.bucketName()).build();
        CfnOutput.Builder.create(this, "DashboardDistributionId").value(hosting.distributionId()).build();
        Tags.of(this).add("Project", "weather");
        Tags.of(this).add("Environment", "dev");
        CfnOutput.Builder.create(this, "QueueUrl").value(queue.getQueueUrl()).build();
        CfnOutput.Builder.create(this, "QueueArn").value(queue.getQueueArn()).build();
        CfnOutput.Builder.create(this, "DeadLetterQueueUrl").value(dlq.getQueueUrl()).build();
        CfnOutput.Builder.create(this, "WorkerName").value(worker.getFunctionName()).build();
        CfnOutput.Builder.create(this, "WorkerLogGroup").value(logs.getLogGroupName()).build();
        CfnOutput.Builder.create(this, "DispatcherName").value(dispatcher.getFunctionName()).build();
        CfnOutput.Builder.create(this, "ScheduleName").value(schedule.getName()).build();
        CfnOutput.Builder.create(this, "DispatchFailureQueueUrl").value(dispatchDlq.getQueueUrl()).build();
    }
}
