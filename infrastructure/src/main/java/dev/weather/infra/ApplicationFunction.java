package dev.weather.infra;

import java.util.Map;
import software.constructs.Construct;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.logs.*;
import software.amazon.awscdk.services.dynamodb.ITable;
import software.amazon.awscdk.services.secretsmanager.ISecret;
import software.amazon.awscdk.services.sqs.IQueue;

public final class ApplicationFunction extends Construct {
    private final Function function;
    public ApplicationFunction(Construct scope, String id, String artifact, ITable table, ISecret secret, IQueue failures) {
        super(scope, id);
        var logs = LogGroup.Builder.create(this, "Logs").logGroupName("/aws/lambda/weather-app-dev")
                .retention(RetentionDays.TWO_WEEKS).removalPolicy(RemovalPolicy.RETAIN_ON_UPDATE_OR_DELETE).build();
        var role = Role.Builder.create(this, "Role").roleName("weather-app-dev-role")
                .permissionsBoundary(ManagedPolicy.fromManagedPolicyArn(this, "Boundary",
                        "arn:aws:iam::471084227270:policy/weather-app-dev-boundary"))
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com")).build();
        function = Function.Builder.create(this, "Function").functionName("weather-app-dev")
                .runtime(software.amazon.awscdk.services.lambda.Runtime.JAVA_21).architecture(Architecture.X86_64)
                .handler("dev.weather.WeatherAppHandler::handleRequest").code(Code.fromAsset(artifact))
                .memorySize(512).timeout(Duration.seconds(180)).role(role).logGroup(logs)
                .loggingFormat(LoggingFormat.JSON).deadLetterQueue(failures).retryAttempts(2).maxEventAge(Duration.hours(1))
                .environment(Map.of("WEATHER_TABLE_NAME", table.getTableName(), "OPENWEATHER_SECRET_ARN", secret.getSecretArn(),
                        "ENVIRONMENT", "dev", "POWERTOOLS_SERVICE_NAME", "weather-app", "POWERTOOLS_LOG_LEVEL", "INFO")).build();
        table.grant(function, "dynamodb:PutItem", "dynamodb:Query");
        secret.grantRead(function);
        logs.grantWrite(role);
    }
    public Function function() { return function; }
}
