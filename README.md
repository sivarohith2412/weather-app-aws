# Weather Atlas

A Java 21 and Angular weather application running on AWS with **one application Lambda**, `weather-app-dev`.

Website: https://d38x2e3vs6ml0b.cloudfront.net

## Runtime

- EventBridge Scheduler invokes the function every five minutes to collect Boston, London, Mumbai, Tokyo, Sydney, and Charlotte.
- The function loops through the shared city catalog, loads the provider key from Secrets Manager, and conditionally inserts observations into DynamoDB.
- API Gateway invokes the same function for public read-only city/latest/history routes. HTTP requests cannot select collection mode through their body or query.
- Angular is served from private S3 through CloudFront and reads the API. Browser refresh does not fetch new provider weather.
- `weather-app-dlq-dev` receives exhausted asynchronous collection and scheduler-delivery failures. It is a failure queue, not a city-job queue.

The single function has 512 MB memory, Java 21, and a 180-second collection timeout. HTTP queries remain bounded and the API integration timeout is 26 seconds. Collection attempts every city before reporting failures; asynchronous retries repeat the run, with duplicate-safe writes. One function can have concurrent execution environments, so an API request does not wait in a single application process behind collection.

## Code

`WeatherAppHandler` is the deployed Lambda entry point. `ReadApiHandler` is reused as a Java HTTP-route component, not deployed as another function. `CollectionService`, `WeatherClient`, `SecretApiKey`, and `DynamoWeatherRepository` implement collection; `DynamoWeatherReader` implements partition queries. `Main` remains a local Boston development client.

`src/main/resources/cities.json` is the configured allowlist. The application currently supports at most ten cities. The frontend supports six cards and should be reviewed if that count changes.

## Checks and local development

```powershell
mvn -B clean package
npm ci
mvn -B -f infrastructure/pom.xml verify
npm run synth
npm ci --prefix frontend
npm test --prefix frontend -- --watch=false
npm run build --prefix frontend
npm start --prefix frontend -- --host 127.0.0.1
```

Build the backend before CDK synth because infrastructure uses `target/weather-lambda.zip`. Local Java uses `OPENWEATHER_API_KEY` from the environment and the appropriate `AWS_PROFILE`; Lambda uses Secrets Manager. Angular's `public/config.json` contains only the public API URL. Do not commit credentials.

## Delivery and operations

GitHub Actions Checks verifies backend, infrastructure, and Angular. Deploy development runs on main or manually, authenticates through OIDC, deploys Java CDK/CloudFormation, tests real collection and API reads, builds/publishes Angular, refreshes CloudFront, and tests the website.

Use **Test weather collection > Run workflow > main** to collect and verify immediately. It can run while automatic collection is paused. To pause collection, manually run Deploy development with `schedule_state: DISABLED`; choose ENABLED to resume. Push deployments preserve the current state.

The application table is `weather-readings-dev`, keyed by cityId/observedAt, with approximately 30-day observation TTL. Identical observation keys return ALREADY_EXISTS successfully. History is recorded weather, not a forecast, and is limited to 24 hours / 500 returned observations.

Current logs: `/aws/lambda/weather-app-dev` in Ohio, retained for 14 days. In Lambda Console choose weather-app-dev > Monitor > View CloudWatch logs. Search by cityId, collectionId, collection_run_failed, or weather_api_completed.

## Infrastructure and access

`WeatherStack.java` owns the application function, Scheduler, API, failure queue, and hosting construct. `ApplicationFunction.java` defines the single function and bounded role. `DashboardHosting.java` defines private encrypted S3 and CloudFront with signed origin access, HTTPS, and managed security headers.

The existing table and provider secret are referenced and preserved. CDK bootstrap resources remain deployment infrastructure. Administrators manage the core deployment policy, application deployment policy, website policies, application boundary, and scheduler boundary under config/. Deployments cannot expand their own boundaries.

The original multi-function implementation and the staged migration are recorded in Git history and config/single-lambda-migration.md. The old dispatcher, worker, reader, and local-test collector resources are retired after verifying the replacement. See the generated project documentation and cleanup report in the local outputs folder for the detailed development record.
