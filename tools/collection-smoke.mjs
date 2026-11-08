import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';

// AWS CLI uses the short-lived OIDC credentials supplied by GitHub Actions.
const aws = (...args) => JSON.parse(execFileSync('aws', [...args, '--region', 'us-east-2', '--output', 'json', '--no-cli-pager'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }));
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
mkdirSync('work', { recursive: true });
const force = process.argv.includes('--force');
const schedule = aws('scheduler', 'get-schedule', '--name', 'weather-collection-every-five-minutes', '--group-name', 'weather-dev');
if (schedule.State === 'DISABLED' && !force) {
  console.log('Collection smoke test skipped: automatic collection is paused. Use Test weather collection for an explicit manual run.');
  process.exit(0);
}
const cities = JSON.parse(readFileSync('src/main/resources/cities.json', 'utf8'));
const input = 'work/smoke-input.json';
const output = 'work/smoke-response.json';
writeFileSync(input, JSON.stringify({ scheduledAt: new Date().toISOString() }));
console.log('Invoking weather-app-dev immediately.');
const invocation = aws('lambda', 'invoke', '--function-name', 'weather-app-dev', '--cli-read-timeout', '210', '--payload', `fileb://${input}`, output);
assert.equal(invocation.FunctionError, undefined, 'Application collection invocation failed');
const dispatched = JSON.parse(readFileSync(output, 'utf8'));
assert.equal(dispatched.successfulCityCount, cities.length, 'Application did not complete every configured city');
assert.match(dispatched.collectionId, /^dev-[0-9TZ:.+-]+$/);
console.log(`Collected ${cities.length} cities; collection ID ${dispatched.collectionId}`);
const deadline = Date.now() + 150_000;
let completed = new Map();
while (Date.now() < deadline) {
  const logs = aws('logs', 'filter-log-events', '--log-group-name', '/aws/lambda/weather-app-dev',
    '--filter-pattern', `{ $.collectionId = "${dispatched.collectionId}" }`);
  const records = (logs.events ?? []).map(event => { try { return JSON.parse(event.message); } catch { return {}; } });
  assert.equal(records.some(event => event.message === 'collection_failed'), false, 'A city collection failed; inspect CloudWatch logs');
  completed = new Map(records.filter(event => event.message === 'collection_completed').map(event => [event.cityId, event]));
  if (cities.every(city => completed.has(city.id))) break;
  console.log(`Completed ${completed.size}/${cities.length}; waiting for log delivery.`);
  await pause(5000);
}
assert.equal(completed.size, cities.length, 'Timed out waiting for all city jobs');
for (const city of cities) {
  const event = completed.get(city.id);
  assert.ok(['SAVED', 'ALREADY_EXISTS'].includes(event.outcome), `Unexpected outcome for ${city.id}`);
  const key = JSON.stringify({ cityId: { S: city.id }, observedAt: { S: event.observedAt } });
  const result = aws('dynamodb', 'get-item', '--table-name', 'weather-readings-dev', '--key', key, '--consistent-read');
  assert.equal(result.Item?.cityId?.S, city.id, `Missing persisted reading for ${city.id}`);
  assert.equal(result.Item.observedAt.S, event.observedAt);
  console.log(`Verified ${city.name}: ${event.outcome}, observation ${event.observedAt}`);
}
console.log(`SMOKE TEST PASSED: one Lambda → ${cities.length} cities → DynamoDB, with correlated CloudWatch logs.`);

