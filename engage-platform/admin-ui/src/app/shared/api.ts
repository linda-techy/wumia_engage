import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** Promise-style calls for actions (a button press); reads use httpResource. */
@Injectable({ providedIn: 'root' })
export class Api {
  readonly #http = inject(HttpClient);

  post<T = unknown>(url: string, body: unknown = {}): Promise<T> {
    return firstValueFrom(this.#http.post<T>(url, body));
  }

  put<T = unknown>(url: string, body: unknown): Promise<T> {
    return firstValueFrom(this.#http.put<T>(url, body));
  }

  patch<T = unknown>(url: string, body: unknown): Promise<T> {
    return firstValueFrom(this.#http.patch<T>(url, body));
  }

  delete<T = unknown>(url: string): Promise<T> {
    return firstValueFrom(this.#http.delete<T>(url));
  }
}

/** "2026-10-09T18:30" typed into a datetime-local box, read as IST → an ISO instant. */
export function istInputToIso(local: string): string | null {
  if (!local) return null;
  return new Date(`${local}:00+05:30`).toISOString();
}
