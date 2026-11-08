import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { City, History, Reading, WeatherApi } from './weather-api';
@Component({ selector: 'app-root', imports: [DatePipe, DecimalPipe], templateUrl: './app.html', styleUrl: './app.css' })
export class App implements OnInit, OnDestroy {
  private api = inject(WeatherApi);
  cities = signal<City[]>([]); readings = signal<Reading[]>([]);
  selectedId = signal('us-boston'); hours = signal(24); history = signal<History | null>(null);
  error = signal(''); historyError = signal(''); loading = signal(false); historyLoading = signal(false);
  lastChecked = signal<Date | null>(null); now = signal(Date.now()); showTable = signal(false);
  selected = computed(() => this.readings().find(r => r.cityId === this.selectedId()));
  selectedCity = computed(() => this.cities().find(c => c.id === this.selectedId()));
  freshCount = computed(() => this.readings().filter(r => !this.stale(r)).length);
  points = computed(() => this.history()?.readings ?? []);
  range = computed(() => { const v = this.points().map(r => r.temperatureC); return v.length ? { min: Math.floor(Math.min(...v)) - 1, max: Math.ceil(Math.max(...v)) + 1 } : { min: 0, max: 1 }; });
  chartPoints = computed(() => this.points().map(r => `${this.x(r.observedAt)},${this.y(r.temperatureC)}`).join(' '));
  private timer?: ReturnType<typeof setInterval>; private request = 0; private destroyed = false; private failures = 0; private nextRefresh = 0;
  ngOnInit() { void this.refresh(); this.timer = setInterval(() => { this.now.set(Date.now()); if (!document.hidden && Date.now() >= this.nextRefresh) void this.refresh(); }, 30000); }
  ngOnDestroy() { this.destroyed = true; this.request++; clearInterval(this.timer); }
  async refresh() {
    if (this.loading()) return; this.loading.set(true);
    try {
      const [cities, latest] = await Promise.all([this.api.cities(), this.api.latest()]);
      if (this.destroyed) return;
      this.cities.set(cities.cities); this.readings.set(latest.readings); this.lastChecked.set(new Date()); this.error.set(''); this.failures = 0;
      await this.loadHistory();
    } catch (e) { if (!this.destroyed) { this.error.set(e instanceof Error ? e.message : 'Could not connect to weather data.'); this.failures++; } }
    finally { this.nextRefresh = Date.now() + Math.min(300000, 30000 * 2 ** this.failures); if (!this.destroyed) this.loading.set(false); }
  }
  select(id: string) { if (id !== this.selectedId()) { this.selectedId.set(id); this.history.set(null); void this.loadHistory(); } }
  setHours(hours: number) { if (hours !== this.hours()) { this.hours.set(hours); this.history.set(null); void this.loadHistory(); } }
  async loadHistory() {
    const token = ++this.request; this.historyLoading.set(true); this.historyError.set('');
    try { const h = await this.api.history(this.selectedId(), this.hours()); if (token === this.request && !this.destroyed) this.history.set(h); }
    catch { if (token === this.request && !this.destroyed) this.historyError.set('Temperature history could not be loaded.'); }
    finally { if (token === this.request && !this.destroyed) this.historyLoading.set(false); }
  }
  reading(id: string) { return this.readings().find(r => r.cityId === id); }
  stale(r: Reading) { return this.now() - Date.parse(r.observedAt) > 30 * 60000; }
  age(r: Reading) { return Math.max(0, Math.floor((this.now() - Date.parse(r.observedAt)) / 60000)); }
  icon(code: number) { return code === 800 ? '☀' : code >= 801 ? '☁' : code >= 700 ? '≋' : code >= 600 ? '❄' : code >= 300 ? '☂' : 'ϟ'; }
  x(time: string) { const h = this.history(); if (!h) return 0; return 36 + (Date.parse(time) - Date.parse(h.from)) / (Date.parse(h.to) - Date.parse(h.from)) * 708; }
  y(temp: number) { const { min, max } = this.range(); return 180 - (temp - min) / (max - min) * 150; }
}
