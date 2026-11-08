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

GitHub Actions checks the frontend on pull requests. Deployment tests the live API, configures and builds Angular, then uploads the `weather-dashboard-dev` artifact. Hosting on S3/CloudFront is a separate next step; the current dashboard runs locally.

Source structure:
- `src/app/app.ts`: screen state, refresh, city/period selection, chart coordinates.
- `src/app/app.html`: responsive dashboard and accessible history table.
- `src/app/app.css`: visual styling and mobile layout.
- `src/app/weather-api.ts`: typed read API client and public runtime configuration.
- `src/app/app.spec.ts`: data display, failed refresh retention, and response ordering checks.

Checks: `npm test --prefix frontend -- --watch=false` and `npm run build --prefix frontend`.
