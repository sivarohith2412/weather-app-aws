package dev.weather.infra;

import java.util.Map;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.iam.ManagedPolicy;
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
        worker.addEventSource(SqsEventSource.Builder.create(queue).batchSize(1)
                .reportBatchItemFailures(true).maxConcurrency(2).build());
        Tags.of(this).add("Project", "weather");
        Tags.of(this).add("Environment", "dev");
        CfnOutput.Builder.create(this, "QueueUrl").value(queue.getQueueUrl()).build();
        CfnOutput.Builder.create(this, "QueueArn").value(queue.getQueueArn()).build();
        CfnOutput.Builder.create(this, "DeadLetterQueueUrl").value(dlq.getQueueUrl()).build();
        CfnOutput.Builder.create(this, "WorkerName").value(worker.getFunctionName()).build();
        CfnOutput.Builder.create(this, "WorkerLogGroup").value(logs.getLogGroupName()).build();
    }
}
