import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
const base = JSON.parse(readFileSync(process.argv[2], 'utf8')).WeatherDev.ReadApiUrl;
assert.match(base, /^https:\/\/[a-z0-9]+\.execute-api\.us-east-2\.amazonaws\.com\/?$/);
const get = path => fetch(base + path, { headers: { Origin: 'http://localhost:4200' }, signal: AbortSignal.timeout(40000) });
const citiesResponse = await get('/cities');
assert.equal(citiesResponse.status, 200);
assert.equal(citiesResponse.headers.get('access-control-allow-origin'), 'http://localhost:4200');
const { cities } = await citiesResponse.json();
assert.equal(cities.length, 5);
const latest = await get('/weather/latest');
assert.equal(latest.status, 200);
const body = await latest.json();
assert.equal(body.readings.length + body.missingCityIds.length, 5);
for (const city of cities) {
  const response = await get('/weather/history?cityId=' + city.id + '&hours=24');
  assert.equal(response.status, 200);
  const data = await response.json();
  assert.equal(data.cityId, city.id);
  assert.ok(Array.isArray(data.readings));
  assert.ok(data.readings.length <= 500);
  assert.ok(data.readings.every(r => r.cityId === city.id));
}
assert.equal((await get('/weather/history?cityId=unknown')).status, 400);
assert.equal((await get('/weather/history?cityId=us-boston&hours=999')).status, 400);
console.log('READ API SMOKE TEST PASSED: cities, latest readings, all five histories, invalid input, and localhost CORS.');
