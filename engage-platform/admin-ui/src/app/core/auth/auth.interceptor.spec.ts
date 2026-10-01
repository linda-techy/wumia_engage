import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { authInterceptor } from './auth.interceptor';
import { TokenService } from './token.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let tokens: TokenService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    tokens = TestBed.inject(TokenService);
  });

  afterEach(() => backend.verify());

  it('adds the bearer token to /api calls only', () => {
    tokens.set('access-1');
    http.get('/api/ingest/health').subscribe();
    http.get('https://example.com/other').subscribe();

    expect(backend.expectOne('/api/ingest/health').request.headers.get('Authorization')).toBe('Bearer access-1');
    expect(backend.expectOne('https://example.com/other').request.headers.has('Authorization')).toBe(false);
  });

  it('parallel 401s share one refresh, then each request is retried with the new token', async () => {
    tokens.set('expired');
    const calls = ['/api/ingest/health', '/api/payments/failures', '/api/consent-copy'].map((url) =>
      firstValueFrom(http.get(url)),
    );

    for (const url of ['/api/ingest/health', '/api/payments/failures', '/api/consent-copy']) {
      backend.expectOne(url).flush({ detail: 'expired' }, { status: 401, statusText: 'Unauthorized' });
    }
    const refreshes = backend.match('/api/auth/refresh');
    expect(refreshes.length).toBe(1);
    refreshes[0].flush({ accessToken: 'access-2' });

    for (const url of ['/api/ingest/health', '/api/payments/failures', '/api/consent-copy']) {
      const retry = backend.expectOne(url);
      expect(retry.request.headers.get('Authorization')).toBe('Bearer access-2');
      retry.flush({ ok: url });
    }
    expect((await Promise.all(calls)).length).toBe(3);
    expect(tokens.token()).toBe('access-2');
  });

  it('a failed refresh signs the operator out and goes to sign-in', async () => {
    tokens.set('expired');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const call = firstValueFrom(http.get('/api/customers/x'));

    backend.expectOne('/api/customers/x').flush({}, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });

    await expect(call).rejects.toBeTruthy();
    expect(tokens.isAuthenticated()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/login'], expect.anything());
  });

  it('never retries an /api/auth call: its 401 is the answer', async () => {
    const call = firstValueFrom(http.post('/api/auth/login', {}));
    backend.expectOne('/api/auth/login').flush({ detail: 'invalid' }, { status: 401, statusText: 'Unauthorized' });
    await expect(call).rejects.toBeTruthy();
    backend.expectNone('/api/auth/refresh');
  });
});
