import { Injectable } from '@angular/core';
export interface City { id: string; name: string; country: string }
export interface Reading { cityId: string; cityName: string; country: string; temperatureC: number; humidityPercent: number; windSpeedMps: number; conditionCode: number; condition: string; observedAt: string; collectedAt: string; provider: string }
export interface Latest { readings: Reading[]; missingCityIds: string[]; generatedAt: string }
export interface History { cityId: string; readings: Reading[]; truncated: boolean; from: string; to: string }
@Injectable({ providedIn: 'root' })
export class WeatherApi {
  private base?: string;
  private async get<T>(path: string): Promise<T> {
    if (!this.base) {
      const response = await fetch('/config.json', { cache: 'no-store', signal: AbortSignal.timeout(8000) });
      if (!response.ok) throw new Error('Dashboard configuration is unavailable.');
      const value = await response.json();
      if (typeof value.apiBaseUrl !== 'string' || !/^https:\/\/[a-z0-9]+\.execute-api\.us-east-2\.amazonaws\.com\/?$/.test(value.apiBaseUrl)) throw new Error('The weather API connection has not been configured yet.');
      this.base = value.apiBaseUrl.replace(/\/$/, '');
    }
    const response = await fetch(this.base + path, { signal: AbortSignal.timeout(35000) });
    if (!response.ok) throw new Error('Weather data is temporarily unavailable. Please try again.');
    return response.json();
  }
  cities() { return this.get<{ cities: City[] }>('/cities'); }
  latest() { return this.get<Latest>('/weather/latest'); }
  history(cityId: string, hours: number) { return this.get<History>(`/weather/history?cityId=${encodeURIComponent(cityId)}&hours=${hours}`); }
}
