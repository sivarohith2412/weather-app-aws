# Weather project

Java 21 weather client with optional AWS DynamoDB persistence, direct Lambda and
SQS worker handlers, Java CDK infrastructure, and GitHub Actions workflows.
EventBridge Scheduler starts five-city collection every five minutes. The read API
and Angular dashboard are planned.

## Scheduled collection

`src/main/resources/cities.json` configures Boston, London, Mumbai, Tokyo and Sydney.
Both the dispatcher and worker use this allowlist. Edit and review the file to change
tracked cities; at most ten cities fit the current single-batch dispatcher.

Scheduler invokes `weather-dispatcher-dev`, which sends one job per city to the
existing collection queue. Workers process at most two city jobs concurrently.
The run's scheduled timestamp determines its collection ID, so retries are correlated.
Individual SQS batch-send failures fail dispatch even when the HTTP response succeeds.
Queue delivery remains at-least-once; existing city/observation items remain unchanged.

The worker DLQ holds repeatedly failing city jobs. `weather-dispatch-dlq-dev` captures
Scheduler delivery failures and exhausted Lambda asynchronous dispatcher failures.
The two failure sources have different retry mechanisms. Dispatcher retries can resend
some city jobs; duplicate observation writes are safe but extra provider calls can occur.

To pause collection, open GitHub Actions > Deploy development > Run workflow on `main`
and choose `schedule_state: DISABLED`. Use ENABLED to resume. Existing readings remain.
Push-triggered deployments preserve the current CloudFormation schedule parameter.
The rate schedule is not aligned to wall-clock five-minute boundaries; delivery can
occur within its scheduled minute. Collected history starts when this app runs.

## Infrastructure and automated delivery

In GitHub Actions, choose **Test weather collection > Run workflow > main** for an
immediate five-city test without redeploying or waiting for the scheduler. It works
even while the schedule is paused. Each run makes approximately five provider calls
before retries, checks correlated logs and confirms the exact readings in DynamoDB.
Deployments run the same smoke test automatically, except when collection is paused.

`infrastructure/` contains Java CDK definitions. `.github/workflows/ci.yml` runs
checks without AWS credentials. `deploy-dev.yml` repeats the checks and deploys
from `main` using GitHub OIDC after the one-time administrator setup.
See [the setup guide](outputs/cicd-setup-guide.md). Existing table/secret resources
and the manually deployed collector are preserved. The new queue worker is separate.

## IntelliJ setup

Open `pom.xml` as a Maven project, reload Maven dependencies, and select Corretto 21
as the project SDK. In Run > Edit Configurations > Main, add these environment variables:

| Name | Value |
| --- | --- |
| OPENWEATHER_API_KEY | Your private OpenWeather Current Weather API key |
| AWS_PROFILE | weather-dev |
| AWS_REGION | us-east-2 |
| WEATHER_TABLE_NAME | weather-readings-dev |

Keep credentials and run configurations containing keys out of source control.
AWS credentials come from your existing browser login profile, not Java source code.
The SDK includes the `signin` module to support that login profile.

## Run modes

Set **Program arguments** in the IntelliJ Main run configuration:

- `--check-db`: verify access to the existing table; does not call OpenWeather or write data.
- No arguments: fetch and print Boston weather without storing it.
- `--save`: fetch real Boston weather and save it to DynamoDB.

Successful writes print `DynamoDB: SAVED`. Repeated observations print
`DynamoDB: ALREADY_EXISTS`; the previous item remains unchanged.
Errors return a nonzero exit status without printing credentials.

The table must use string keys `cityId` and `observedAt`, with TTL enabled on `expiresAt`.
Java writes a numeric expiry 30 days after provider observation time. TTL deletion
is asynchronous. The repository uses a conditional insert to handle duplicates.

## Tests

Run `mvn test` with Java 21, or run the tests under `src/test/java` in IntelliJ.
Tests use simulated provider responses and a fake DynamoDB client: no cloud writes
or weather-provider calls occur during tests.

## Lambda and logging

`mvn package` builds `target/weather-lambda.zip`, with intact dependency JARs under
`lib/`. The handler is `dev.weather.CollectorHandler::handleRequest` (Java 21).
See `outputs/lambda-deployment-guide.md` for the deployment and secret setup.

Both local and Lambda collection now use Powertools/Log4j2 JSON logging. The SLF4J
backend is configured. Set `POWERTOOLS_LOG_LEVEL=DEBUG` for extra diagnostics and
`POWERTOOLS_SERVICE_NAME=weather-collector` to label local logs. The normal console
reading JSON and `DynamoDB: ...` summary remain for local runs.

To inspect a saved reading, sign in as an administrator, open DynamoDB in Ohio,
select `weather-readings-dev`, and choose Explore table items. The development
identity intentionally lacks broad table-browsing and administrative permissions.

Provider: [OpenWeather](https://openweathermap.org/). Weather data supplied by OpenWeather.
