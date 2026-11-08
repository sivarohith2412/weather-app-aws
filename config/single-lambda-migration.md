# Single-Lambda migration

The application is being simplified to `weather-app-dev`, using `dev.weather.WeatherAppHandler::handleRequest`.

HTTP API events use the existing read API code. Scheduled/manual `scheduledAt` events collect all configured cities directly. HTTP body/query fields cannot select collection mode. The same function therefore needs the combined Query/PutItem and provider-secret permissions, capped by `weather-app-dev-boundary`.

Collection attempts every city, records individual failures, then fails the invocation if any city failed. Lambda asynchronous retries repeat the whole run; conditional writes keep duplicate observations safe. This replaces SQS city-job retries. One `weather-app-dlq-dev` queue receives exhausted collection and schedule-delivery failures.

The function has 512 MB memory and a 180-second timeout for the collection loop. HTTP routes retain their existing bounded DynamoDB timeouts and 26-second API integration timeout. The browser still reads saved data; it never initiates collection.

Migration stages:

1. Add the combined function and its bounded role, switch Scheduler/API and workflow smoke tests to it, and verify real collection, API reads, and the website. Keep old functions temporarily available for rollback.
2. Remove the old stack-managed dispatcher/worker/reader and collection-queue resources, verify the one-function stack, then clean up the unused legacy collector, retained empty queues/log groups, and unused roles/policies after checking references and saving a recovery record.

The database, provider secret, API URL, and website hosting remain part of the application. CDK bootstrap resources remain deployment infrastructure. Old website hashes are retained for browsers that still have earlier pages open.

Stage 1 completed successfully through GitHub Actions, including live six-city collection, API, and website tests. The follow-up source removes the three old stack functions and obsolete entry-point classes, SQS collection dependencies, and old policy definitions. Exactly one function and one active failure queue remain in the desired stack. Historic retained resources are cleaned up only after the stack deletion and reference/queue checks. The core execution policy is trimmed after cleanup; application and website deployment policies remain active.
