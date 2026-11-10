import { Injectable, Injector, inject } from '@angular/core';

const ERROR_DURATION_MS = 8000;
const INFO_DURATION_MS = 5000;

/**
 * Snackbar messages. The same text is not shown again while it is still on screen, so a burst of
 * failing requests produces one message. Material's snackbar announces through a live region;
 * errors are assertive, information is polite. Material loads on first use, outside the initial bundle.
 */
@Injectable({ providedIn: 'root' })
export class Notifier {
  private readonly injector = inject(Injector);
  private lastMessage = '';
  private lastExpiry = 0;

  error(message: string): Promise<void> {
    return this.show(message, 'assertive', ERROR_DURATION_MS);
  }

  info(message: string): Promise<void> {
    return this.show(message, 'polite', INFO_DURATION_MS);
  }

  private async show(
    message: string,
    politeness: 'polite' | 'assertive',
    duration: number,
  ): Promise<void> {
    const now = Date.now();
    if (message === this.lastMessage && now < this.lastExpiry) {
      return;
    }
    this.lastMessage = message;
    this.lastExpiry = now + duration;
    const { MatSnackBar } = await import('@angular/material/snack-bar');
    this.injector.get(MatSnackBar).open(message, 'Dismiss', {
      duration,
      politeness,
      horizontalPosition: 'center',
      verticalPosition: 'bottom',
    });
  }
}
