# Website deployment access

These policies extend the existing deployment roles for S3/CloudFront hosting. They are installed once by an administrator and are kept outside the application stack so that application deployments cannot expand their own permissions.

- `website-deployment-policy.json`, managed policy `weather-website-deployment-dev`: attached to the existing CloudFormation execution role. S3 access is limited to `weather-dashboard-dev-471084227270-us-east-2`. CloudFront distribution management is limited to this account and the `Project=weather` resource tag. Creation requires `Project=weather` and `Environment=dev` request tags. Origin access control management is limited to OAC resources in this account.
- `website-publish-policy.json`, managed policy `weather-website-publish-dev`: attached to `weather-github-deploy-dev`. Allows list/upload/read on the named website bucket and invalidation/status checks on project-tagged distributions. It cannot delete website files or change bucket or distribution configuration.

AWS requires `Resource: "*"` for CloudFront distribution creation and OAC creation because their identifiers do not exist yet and the IAM actions have no resource type. Distribution creation is constrained by request tags. OAC creation does not support resource or request-tag conditions; subsequent OAC actions use the account-specific ARN pattern. No wildcard service action is granted.

The S3 bucket remains private with all Block Public Access settings enabled and bucket owner enforcement. Its policy grants `s3:GetObject` to the CloudFront service only with the exact distribution source ARN. Uploads use short-lived GitHub OIDC credentials. Angular receives only the public HTTP API URL.

The bucket uses RetainExceptOnCreate: an unsuccessful first creation can clean up the empty bucket; an established bucket is retained when removed from the stack. Publishing retains old hashed assets, uploads the new page last, and waits for invalidation before testing the site. There is no automatic import of existing resources and no automatic deletion of old website assets.
