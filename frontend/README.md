# Angular dashboard

Run from the repository root:

```powershell
npm ci --prefix frontend
node tools/configure-dashboard.mjs deployment-outputs.json
npm start --prefix frontend
```

Open http://localhost:4200. The configuration script reads the `ReadApiUrl` output of the deployed `WeatherDev` stack. To retrieve the outputs locally, use your authorized AWS profile and save `{ "WeatherDev": { "ReadApiUrl": "https://YOUR_API_ID.execute-api.us-east-2.amazonaws.com" } }` as `deployment-outputs.json`.

The dashboard has five city cards, temperature/humidity/wind details, observation and collection times, 6/12/24-hour temperature history, a data table, a manual refresh button, and automatic refresh while visible. Observations older than 30 minutes are marked. A failed refresh keeps the last good data, and retries slow down to a maximum five-minute interval. Switching city or period discards late responses for the old selection.

`config.json` contains only the public API URL. Never place an OpenWeather key or AWS credentials in this project. The API exposes read-only weather data publicly; CORS is a browser rule, not authentication. Queries are limited to configured cities and a maximum 24-hour history, returning the newest 500 observations and an explicit truncation flag if needed. There is no forecast endpoint.

GitHub Actions checks the frontend on pull requests. Deployment tests the live API, configures and builds Angular, uploads files to the private S3 bucket, refreshes CloudFront, and smoke-tests the public website. It also saves the `weather-dashboard-dev` artifact. Open the `WebsiteUrl` stack output to use the hosted dashboard, or run locally using the commands above.

The page and runtime configuration are not cached by CloudFront. Hashed JavaScript/CSS are uploaded first and cached for a year; the page/configuration are uploaded last. Older hashed assets are retained for already-open pages. The bucket blocks public access and allows CloudFront read access only for this distribution through OAC. Direct S3 object requests are denied. HTTPS is enforced and CloudFront adds managed security headers. The API CORS allowlist includes the hosted website origin and the two local development origins.

Source structure:
- `src/app/app.ts`: screen state, refresh, city/period selection, chart coordinates.
- `src/app/app.html`: responsive dashboard and accessible history table.
- `src/app/app.css`: visual styling and mobile layout.
- `src/app/weather-api.ts`: typed read API client and public runtime configuration.
- `src/app/app.spec.ts`: data display, failed refresh retention, and response ordering checks.

Checks: `npm test --prefix frontend -- --watch=false` and `npm run build --prefix frontend`.
