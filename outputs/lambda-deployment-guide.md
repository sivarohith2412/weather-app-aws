# Deploy the first weather collector Lambda

The Java 21 collector and detailed JSON logging are ready for the first manual AWS
deployment. This milestone supports one configured city (Boston) and direct test
invocations. There is no schedule or queue yet.

Region: **US East (Ohio), us-east-2**. Account: **471084227270**.
Existing table: **weather-readings-dev**. Keep this table; do not recreate it.

The `weather-dev` profile is intentionally limited to table operations and browser
login. Deployment requires your administrator console session. Its Lambda access
was checked and denied; no Lambda resources were created by this build.

## 1. Store the weather key

In your administrator console session in Ohio:

1. Open Secrets Manager > Store a new secret.
2. Select Other type of secret.
3. Add key `OPENWEATHER_API_KEY` and your actual OpenWeather key as its value.
4. Use the default `aws/secretsmanager` encryption key.
5. Name the secret `weather/dev/openweather`.
6. Leave automatic rotation disabled. Store the secret.
7. Copy its full ARN. Keep the key value private; only the ARN is needed below.

Secrets Manager has a recurring charge; the project estimate already includes one
secret. The collector caches its value for five minutes in each warm execution environment.

## 2. Create the log group

1. Open CloudWatch > Logs > Log groups > Create log group.
2. Enter `/aws/lambda/weather-collector-dev`.
3. Select Standard log class and create it.
4. Set retention to **14 days**.

## 3. Create the execution role

1. Open IAM > Roles > Create role.
2. Choose AWS service, then Lambda as the use case.
3. Skip selecting managed permission policies; add the scoped inline policy below
   after creation. Name the role `weather-collector-dev-role`.
4. Open the role > Permissions > Add permissions > Create inline policy > JSON.
5. Paste the following, replacing `arn:aws:secretsmanager:us-east-2:471084227270:secret:weather/dev/openweather-P4uYlV` with the ARN from step 1.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "SaveWeatherReadings",
      "Effect": "Allow",
      "Action": "dynamodb:PutItem",
      "Resource": "arn:aws:dynamodb:us-east-2:471084227270:table/weather-readings-dev"
    },
    {
      "Sid": "ReadWeatherApiKey",
      "Effect": "Allow",
      "Action": "secretsmanager:GetSecretValue",
      "Resource": "arn:aws:secretsmanager:us-east-2:471084227270:secret:weather/dev/openweather-P4uYlV"
    },
    {
      "Sid": "WriteCollectorLogs",
      "Effect": "Allow",
      "Action": ["logs:CreateLogStream", "logs:PutLogEvents"],
      "Resource": "arn:aws:logs:us-east-2:471084227270:log-group:/aws/lambda/weather-collector-dev:*"
    }
  ]
}
```

6. Name the inline policy `WeatherCollectorRuntimeAccess` and create it.
7. Under Trust relationships, confirm Lambda is trusted. The expected policy is:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Service": "lambda.amazonaws.com"},
    "Action": "sts:AssumeRole"
  }]
}
```

This role grants table writes, retrieval of one secret, and writes to one log group.
It does not need your local `AWS_PROFILE` or an AWS access key.

## 4. Create and configure the function

1. Open Lambda in Ohio > Create function > Author from scratch.
2. Function name: `weather-collector-dev`.
3. Runtime: **Java 21**. Architecture: **x86_64**.
4. Under Change default execution role, choose Use an existing role and select
   `weather-collector-dev-role`.
5. Create the function. Do not enable a Function URL or attach it to a VPC.
6. Under Code > Upload from > .zip or .jar file, select `weather-lambda.zip`
   beside this guide. Upload the ZIP as-is; it already contains its `lib` directory.
7. Runtime settings > Edit > Handler: `dev.weather.CollectorHandler::handleRequest`.
8. Configuration > General configuration: memory **512 MB**, timeout **45 seconds**.
9. Configuration > Environment variables:

| Name | Value |
| --- | --- |
| WEATHER_TABLE_NAME | weather-readings-dev |
| OPENWEATHER_SECRET_ARN | The full secret ARN from step 1 |
| ENVIRONMENT | dev |
| POWERTOOLS_SERVICE_NAME | weather-collector |
| POWERTOOLS_LOG_LEVEL | INFO |

Do not set `AWS_PROFILE` or the plaintext API key on the Lambda. AWS supplies its region
and temporary execution-role credentials automatically.

10. Monitoring and operations tools / Logging configuration: use log group
    `/aws/lambda/weather-collector-dev`; choose JSON log format and INFO application
    log level if these options are shown. Application logs are already JSON.
11. Leave provisioned concurrency disabled. Use on-demand Lambda execution: this
    short Java job has idle time between calls and does not need always-running capacity.

## 5. Test one collection

Create a private test event:

```json
{
  "schemaVersion": 1,
  "cityId": "us-boston",
  "collectionId": "dev-first-lambda-test"
}
```

Choose Test. The response should contain `outcome` equal to `SAVED` or
`ALREADY_EXISTS`, the city ID, collection ID and provider observation timestamp.
Repeated test calls still contact OpenWeather, but repeated observations do not
create duplicate database items. Reuse the collection ID only when retrying the same
test; use a new ID for a separate test run.

Open Monitor > View CloudWatch logs. Expected events:

| Event | Meaning |
| --- | --- |
| collection_started | Collection started for a city and collection ID |
| secret_cache_refreshed | Secret fetched successfully and cached |
| credential_ready | Credential retrieval duration, without its value |
| provider_response | HTTP status and provider request duration |
| weather_received | Normalized numeric weather fields and both timestamps |
| reading_saved | Write duration, table name and DynamoDB request ID |
| duplicate_reading_skipped | Existing observation preserved |
| collection_completed | Total duration, save duration and outcome |
| collection_failed | Failed stage, safe error category and exception type |
| invalid_collection_job | Invalid schema or unconfigured city rejected before collection |

The handler adds Lambda request ID, function identity and cold-start context through
Powertools. Collection logs include environment, city ID and collection ID.
DEBUG enables additional start/cache diagnostics. INFO remains the default to limit volume.
Provider request URLs, API keys, raw provider payloads, secret contents, incoming
events, and raw exception messages/causes are not logged. SDK/HTTP wire logs are disabled.

CloudWatch Logs Insights example:

```text
fields @timestamp, message, cityId, collectionId, function_request_id, outcome, stage, errorCategory, durationMs
| filter collectionId = "dev-first-lambda-test"
| sort @timestamp asc
```

## Troubleshooting

- `AUTHENTICATION`: verify the stored key and its activation; a warm cached key can
  take up to five minutes to refresh after editing the secret.
- `AWS_HTTP_403` at secret or DynamoDB stage: check the execution role policy and
  the exact secret/table ARNs. Local user permissions do not transfer to Lambda.
- Missing logs: check log group spelling, role log permissions and retention settings.
- Invalid job: use the exact event shown above. Only Boston is configured in this milestone.
- Timeout: check the failed stage/timing; do not add a NAT Gateway or VPC for this setup.

## Status and next milestone

Local unit tests and package checks can verify the code. A real AWS Lambda test must
still confirm runtime, execution role, secret retrieval and CloudWatch delivery together.
After this vertical slice works, add SQS, the dispatcher, five cities and the schedule.
The eventual repeatable infrastructure remains planned as Java CDK; this first deployment
is manual so the individual AWS resources are visible while learning.

References: [Java packaging](https://docs.aws.amazon.com/lambda/latest/dg/java-package.html),
[execution roles](https://docs.aws.amazon.com/lambda/latest/dg/lambda-intro-execution-role.html),
[creating a secret](https://docs.aws.amazon.com/secretsmanager/latest/userguide/create_secret.html),
[Powertools logging](https://docs.aws.amazon.com/powertools/java/latest/core/logging/).

