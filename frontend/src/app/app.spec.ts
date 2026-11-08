import { TestBed } from '@angular/core/testing';
import { App } from './app';
import { History, Reading, WeatherApi } from './weather-api';
import { vi } from 'vitest';

const reading: Reading = { cityId: 'us-boston', cityName: 'Boston', country: 'US', temperatureC: 12.5, humidityPercent: 90, windSpeedMps: 2.1, conditionCode: 701, condition: 'mist', observedAt: new Date().toISOString(), collectedAt: new Date().toISOString(), provider: 'openweather' };
const history: History = { cityId: 'us-boston', readings: [reading], truncated: false, from: new Date(Date.now()-86400000).toISOString(), to: new Date().toISOString() };
describe('Dashboard data behavior', () => {
  let api: { cities: ReturnType<typeof vi.fn>; latest: ReturnType<typeof vi.fn>; history: ReturnType<typeof vi.fn> };
  beforeEach(async () => {
    api = { cities: vi.fn().mockResolvedValue({ cities: [{id:'us-boston',name:'Boston',country:'US'}] }), latest: vi.fn().mockResolvedValue({readings:[reading],missingCityIds:[]}), history: vi.fn().mockResolvedValue(history) };
    await TestBed.configureTestingModule({imports:[App],providers:[{provide:WeatherApi,useValue:api}]}).compileComponents();
  });
  it('renders saved weather and the history data table', async () => {
    const fixture = TestBed.createComponent(App); fixture.detectChanges(); await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false)); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Boston'); expect(fixture.nativeElement.textContent).toContain('12.5');
    fixture.componentInstance.showTable.set(true); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('table tbody tr')).toBeTruthy();
  });
  it('keeps the last good readings when refresh fails', async () => {
    const fixture = TestBed.createComponent(App); fixture.detectChanges(); await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    api.latest.mockRejectedValueOnce(new Error('Connection unavailable'));
    await fixture.componentInstance.refresh(); fixture.detectChanges();
    expect(fixture.componentInstance.readings()).toEqual([reading]);
    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('Connection unavailable');
  });
  it('discards history responses for a city that is no longer selected', async () => {
    const fixture = TestBed.createComponent(App); const app = fixture.componentInstance;
    let resolve!: (value: History) => void;
    api.history.mockReturnValueOnce(new Promise<History>(r => resolve = r));
    const pending = app.loadHistory();
    api.history.mockResolvedValueOnce({...history,cityId:'gb-london'});
    app.select('gb-london'); await Promise.resolve();
    resolve(history); await pending;
    expect(app.history()?.cityId).toBe('gb-london');
  });
});

