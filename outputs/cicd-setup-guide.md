# Java CDK and GitHub Actions

Repository: https://github.com/sivarohith2412/weather-app-aws

## What is implemented

- Java backend with local, direct Lambda and SQS Lambda entry points.
- Structured JSON diagnostics and credential-safe error handling.
- Java CDK stack defining a new `weather-worker-dev` Lambda, SQS source queue,
  dead-letter queue, execution role, event source mapping and log group.
- Pull-request checks that build, test and synthesize infrastructure without AWS credentials.
- Main-branch deployments using GitHub OIDC temporary credentials.

The stack references your existing DynamoDB table and Secrets Manager secret. They
are not managed, replaced, or deleted by this stack. The manually created
`weather-collector-dev` Lambda remains separate while the new queue worker is verified.

There is no dispatcher, five-city setup, schedule, read API or Angular dashboard yet.

## Local commands

From the repository root with Java 21 and Node 22 installed:

```text
mvn package
npm ci
mvn -f infrastructure/pom.xml verify
npm run synth
```

`synth` generates CloudFormation templates locally; it does not deploy AWS resources.
Use `npm run diff` to preview changes once AWS access and bootstrapping are configured.
`npm run deploy:dev` deploys the development stack; it can create billable AWS resources.

## One-time administrator setup

The current `weather-dev` identity has database-only access. It cannot bootstrap
CDK, create deployment roles, or deploy this stack. An administrator must perform
the initial setup. This is separate from everyday deployment.

1. Create the administrator-owned managed policies `weather-worker-dev-boundary`
   from `config/worker-permissions-boundary.json` and `weather-cdk-execution-dev`
   from `config/cdk-execution-policy.json`. They cap runtime access and scope
   CloudFormation deployment to the weather worker, queues and logs. Validate
   policy documents with IAM Access Analyzer before applying them.
2. Bootstrap CDK in account `471084227270`, region `us-east-2`, with standard
   qualifier `hnb659fds`, termination protection, and the custom execution policy:

   ```text
   npx cdk bootstrap aws://471084227270/us-east-2 --profile weather-setup --cloudformation-execution-policies arn:aws:iam::471084227270:policy/weather-cdk-execution-dev --termination-protection
   ```

   CDK creates its asset bucket and deployment roles. The execution role can create
   the worker role only with the specified permissions boundary. Its event-source
   mapping management includes region/account-scoped mapping ARNs because mapping
   IDs are assigned during creation. Future services require reviewed policy changes.
   The default bootstrap uses AdministratorAccess, so always pass the custom policy.
   If the local CDK CLI cannot read a browser login session, generate the same template
   with `npx cdk bootstrap --show-template --app cdk.out --no-notices` and deploy it
   using AWS CLI CloudFormation with the `CloudFormationExecutionPolicies` parameter
   set to the custom policy ARN, `CAPABILITY_NAMED_IAM`, region `us-east-2`, and
   termination protection enabled. Do not accept the template's default execution policy.
3. In IAM, create (or reuse if already present) the OpenID Connect provider:
   provider URL `https://token.actions.githubusercontent.com`, audience `sts.amazonaws.com`.
4. Create role `weather-github-deploy-dev` with the trust policy in
   `config/github-oidc-trust.json` and inline permissions in
   `config/github-deploy-permissions.json`. The trust permits only this repository's
   `main` branch and uses the immutable owner/repository IDs required for repositories
   created after July 15, 2026. This repository was created in November 2026.
5. In the GitHub repository, open Settings > Secrets and variables > Actions > Variables.
   Add repository variable `AWS_DEPLOY_ROLE_ARN` with value
   `arn:aws:iam::471084227270:role/weather-github-deploy-dev`.
6. Review and merge the code. A push to `main` runs the development deployment. A
   deliberate manual workflow run on `main` is also supported.

The deployment job is intentionally skipped until `AWS_DEPLOY_ROLE_ARN` is set.
Do not store the weather API key or long-lived AWS access keys in GitHub. The Lambda
reads the existing secret at runtime using its execution role.

## SQS behavior

- Standard queue `weather-collection-dev`, encrypted with SQS-managed encryption.
- Visibility timeout 270 seconds: six times the worker's 45-second timeout.
- Batch size 1, maximum event-source concurrency 2, partial batch failures enabled.
- Failed messages become available again after visibility timeout; receive limit 5.
- DLQ `weather-collection-dlq-dev` retains messages for 14 days.
- Queue policies deny non-TLS access. Logs retain 14 days.
- Queue and log resources are retained on stack deletion; retained resources can
  continue costing money and must be reviewed deliberately when cleaning up.

## First asynchronous test after deployment

Send a message through the source queue's Send and receive messages page:

```json
{"schemaVersion":1,"cityId":"us-boston","collectionId":"dev-first-sqs-test"}
```

Lambda will process it automatically. In `/aws/lambda/weather-worker-dev`, look for
`sqs_batch_started`, `sqs_message_started`, collection events, and
`sqs_message_completed`. An empty `batchItemFailures` means the messages succeeded;
failed identifiers cause retries. SQS can deliver duplicates, and database writes
remain conditional to preserve existing observations. Repeated processing can still
make another provider request.

To demonstrate failure handling, deliberately send a message with an unknown city.
It should not call the provider. It will retry and eventually reach the DLQ, which
can take tens of minutes with this visibility timeout. Inspect the reason before
redriving. Do not connect the DLQ itself to the worker. Partial failures may not
increment the Lambda Errors metric; inspect failedCount logs and DLQ metrics too.

## Validation status

Local tests and synthesis are separate from a real GitHub deployment. The initial
OIDC trust, bootstrap execution permissions, GitHub variable and successful workflow
run still need verification in the actual AWS account and repository.

References: https://docs.aws.amazon.com/cdk/v2/guide/work-with-cdk-java.html,
https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws,
https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-configure.html.
