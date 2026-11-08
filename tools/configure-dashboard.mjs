import { readFileSync, writeFileSync } from 'node:fs';
const path = process.argv[2];
if (!path) throw new Error('Pass the CDK deployment outputs JSON file');
const url = JSON.parse(readFileSync(path, 'utf8')).WeatherDev.ReadApiUrl;
if (!/^https:\/\/[a-z0-9]+\.execute-api\.us-east-2\.amazonaws\.com\/?$/.test(url)) throw new Error('Expected the Ohio HTTP API URL');
writeFileSync('frontend/public/config.json', JSON.stringify({ apiBaseUrl: url }) + '\n');
console.log('Dashboard configured with the deployed read API. No credentials are included.');
