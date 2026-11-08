import { execFileSync } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import assert from 'node:assert/strict';

const outputs = JSON.parse(readFileSync(process.argv[2], 'utf8')).WeatherDev;
const bucket = outputs.DashboardBucketName;
const distribution = outputs.DashboardDistributionId;
const directory = 'frontend/dist/weather-dashboard/browser';
assert.equal(bucket, 'weather-dashboard-dev-471084227270-us-east-2');
assert.match(distribution, /^[A-Z0-9]{10,32}$/);
assert.ok(existsSync(`${directory}/index.html`) && existsSync(`${directory}/config.json`));
const aws = (...args) => JSON.parse(execFileSync('aws', [...args, '--region', 'us-east-2', '--output', 'json', '--no-cli-pager'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }) || '{}');

// Upload assets first; retain older hashes so already-open pages keep working.
aws('s3', 'sync', directory, `s3://${bucket}/`, '--exclude', '*', '--include', '*.js', '--include', '*.css',
  '--cache-control', 'public,max-age=31536000,immutable', '--only-show-errors');
aws('s3', 'sync', directory, `s3://${bucket}/`, '--exclude', '*.js', '--exclude', '*.css', '--exclude', 'index.html', '--exclude', 'config.json',
  '--cache-control', 'public,max-age=300', '--only-show-errors');
aws('s3', 'cp', `${directory}/config.json`, `s3://${bucket}/config.json`, '--content-type', 'application/json', '--cache-control', 'no-store', '--only-show-errors');
aws('s3', 'cp', `${directory}/index.html`, `s3://${bucket}/index.html`, '--content-type', 'text/html', '--cache-control', 'no-cache', '--only-show-errors');
const invalidation = aws('cloudfront', 'create-invalidation', '--distribution-id', distribution, '--paths', '/*');
console.log('Dashboard uploaded. Waiting for the CloudFront cache refresh.');
aws('cloudfront', 'wait', 'invalidation-completed', '--distribution-id', distribution, '--id', invalidation.Invalidation.Id);
console.log(`Dashboard published: ${outputs.WebsiteUrl}`);
