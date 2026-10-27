import { HttpClient } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Api } from '../../shared/api';
import { problemDetail } from '../../shared/format';

type State = 'checking' | 'unsupported' | 'install' | 'denied' | 'off' | 'on' | 'busy';

/** base64url → bytes, for PushManager.subscribe's applicationServerKey. */
export function keyBytes(b64url: string): Uint8Array<ArrayBuffer> {
  const b64 = b64url.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (b64url.length % 4)) % 4);
  const raw = atob(b64);
  const out = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

/**
 * New-message alerts on this phone or computer (V50). The browser subscribes
 * with Engage's public key; the worker pings it and /staff-sw.js shows
 * "New WhatsApp message". iPhones allow this only from the Home Screen app.
 */
@Component({
  selector: 'app-staff-alerts',
  template: `
    @switch (state()) {
      @case ('on') {
        <span class="text-xs text-emerald-700">🔔 Alerts on for this device</span>
        <button type="button" class="ml-2 text-xs underline" (click)="disable()">Turn off</button>
      }
      @case ('off') {
        <button type="button" class="rounded border border-emerald-600 px-2 py-1 text-xs text-emerald-800" (click)="enable()">
          🔔 Alert me about new messages on this device
        </button>
      }
      @case ('busy') { <span class="text-xs text-slate-500">Setting up alerts…</span> }
      @case ('denied') {
        <span class="text-xs text-amber-800">Alerts are blocked for this site. Allow notifications in the browser's site settings, then reload.</span>
      }
      @case ('install') {
        <span class="text-xs text-slate-600">For alerts on iPhone: tap Share → Add to Home Screen, open WUMIKA from there and turn alerts on.</span>
      }
      @case ('unsupported') { <span class="text-xs text-slate-500">This browser cannot show alerts.</span> }
    }
    @if (error()) { <p class="mt-1 text-xs text-red-700" role="alert">{{ error() }}</p> }
  `,
})
export class StaffAlerts implements OnInit {
  readonly #api = inject(Api);
  readonly #http = inject(HttpClient);
  readonly state = signal<State>('checking');
  readonly error = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) {
      const ios = /iPhone|iPad|iPod/.test(navigator.userAgent);
      this.state.set(ios ? 'install' : 'unsupported');
      return;
    }
    if (Notification.permission === 'denied') {
      this.state.set('denied');
      return;
    }
    try {
      const sub = await this.#subscription();
      if (sub && Notification.permission === 'granted') {
        const r = await this.#api.post<{ on: boolean }>('/api/staff-push/status', { endpoint: sub.endpoint });
        this.state.set(r.on ? 'on' : 'off');
      } else {
        this.state.set('off');
      }
    } catch {
      this.state.set('off');
    }
  }

  async enable(): Promise<void> {
    this.error.set(null);
    this.state.set('busy');
    try {
      const permission = await Notification.requestPermission();
      if (permission !== 'granted') {
        this.state.set(permission === 'denied' ? 'denied' : 'off');
        return;
      }
      const reg = await navigator.serviceWorker.register('/staff-sw.js', { scope: '/' });
      await navigator.serviceWorker.ready;
      const { publicKey } = await firstValueFrom(this.#http.get<{ publicKey: string }>('/api/staff-push/key'));
      let sub = await reg.pushManager.getSubscription();
      if (!sub) sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(publicKey) });
      await this.#api.post('/api/staff-push/subscribe', { endpoint: sub.endpoint });
      this.state.set('on');
    } catch (e) {
      this.error.set(problemDetail(e, 'Alerts could not be turned on in this browser.'));
      this.state.set('off');
    }
  }

  async disable(): Promise<void> {
    this.error.set(null);
    this.state.set('busy');
    try {
      const sub = await this.#subscription();
      if (sub) {
        await this.#api.post('/api/staff-push/unsubscribe', { endpoint: sub.endpoint });
        await sub.unsubscribe();
      }
    } catch (e) {
      this.error.set(problemDetail(e, 'Alerts could not be turned off.'));
    }
    this.state.set('off');
  }

  async #subscription(): Promise<PushSubscription | null> {
    const reg = await navigator.serviceWorker.getRegistration('/');
    return reg ? reg.pushManager.getSubscription() : null;
  }
}
