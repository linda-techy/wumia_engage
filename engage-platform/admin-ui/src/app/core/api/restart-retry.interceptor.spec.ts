import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { restartRetryInterceptor } from './restart-retry.interceptor';

describe('restartRetryInterceptor', () => {
  let http: HttpClient;
  let mock: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([restartRetryInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    mock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => vi.useRealTimers());

  it('retries a read through a restart', async () => {
    const result = firstValueFrom(http.get<{ ok: boolean }>('/api/dashboard'));
    mock.expectOne('/api/dashboard').flush(null, { status: 502, statusText: 'Bad Gateway' });
    await vi.advanceTimersByTimeAsync(1000);
    mock.expectOne('/api/dashboard').flush(null, { status: 503, statusText: 'Restarting' });
    await vi.advanceTimersByTimeAsync(2000);
    mock.expectOne('/api/dashboard').flush({ ok: true });
    await expect(result).resolves.toEqual({ ok: true });
  });

  it('never retries a write or a real error', async () => {
    const post = firstValueFrom(http.post('/api/inbox/x/reply', {}));
    mock.expectOne('/api/inbox/x/reply').flush(null, { status: 502, statusText: 'Bad Gateway' });
    await expect(post).rejects.toMatchObject({ status: 502 });

    const notFound = firstValueFrom(http.get('/api/customers/x'));
    mock.expectOne('/api/customers/x').flush(null, { status: 404, statusText: 'Not Found' });
    await expect(notFound).rejects.toMatchObject({ status: 404 });
    mock.verify();
  });
});
