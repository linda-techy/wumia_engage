import { Component, input, output } from '@angular/core';
import { problemDetail } from './format';

/** Loading placeholder for a panel. */
@Component({
  selector: 'app-loading',
  template: `<div class="animate-pulse space-y-2 py-4" role="status" aria-label="Loading">
    @for (i of [1, 2, 3]; track i) {
      <div class="h-4 rounded bg-slate-200"></div>
    }
  </div>`,
})
export class LoadingState {}

/** An error with its problem+json detail and a retry button. */
@Component({
  selector: 'app-error',
  template: `<div class="rounded border border-red-200 bg-red-50 p-4 text-sm text-red-800" role="alert">
    <p>{{ message() }}</p>
    @if (retryable()) {
      <button type="button" class="mt-2 font-medium underline" (click)="retry.emit()">Try again</button>
    }
  </div>`,
})
export class ErrorState {
  readonly error = input<unknown>();
  readonly retryable = input(true);
  readonly retry = output<void>();
  message(): string {
    return problemDetail(this.error());
  }
}

/** Nothing to show, said plainly. */
@Component({
  selector: 'app-empty',
  template: `<p class="py-6 text-center text-sm text-slate-500">{{ text() }}</p>`,
})
export class EmptyState {
  readonly text = input('Nothing here yet.');
}
