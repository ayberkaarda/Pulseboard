import { DOCUMENT } from '@angular/common';
import { Injectable, effect, inject, signal } from '@angular/core';

export type ThemePreference = 'system' | 'light' | 'dark';

export const THEME_STORAGE_KEY = 'pulseboard.theme';

const PREFERENCES: readonly ThemePreference[] = ['system', 'light', 'dark'];

function isThemePreference(value: string | null): value is ThemePreference {
  return value !== null && (PREFERENCES as readonly string[]).includes(value);
}

/**
 * Holds the viewer's theme preference and mirrors it onto <html data-theme>.
 * "system" removes the attribute so the stylesheet follows the OS setting.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly document = inject(DOCUMENT);
  private readonly preferenceState = signal<ThemePreference>(this.readStoredPreference());

  readonly preference = this.preferenceState.asReadonly();

  constructor() {
    effect(() => {
      const preference = this.preferenceState();
      const root = this.document.documentElement;
      if (preference === 'system') {
        root.removeAttribute('data-theme');
      } else {
        root.setAttribute('data-theme', preference);
      }
      this.writeStoredPreference(preference);
    });
  }

  setPreference(preference: ThemePreference): void {
    this.preferenceState.set(preference);
  }

  private readStoredPreference(): ThemePreference {
    try {
      const stored = this.document.defaultView?.localStorage.getItem(THEME_STORAGE_KEY) ?? null;
      return isThemePreference(stored) ? stored : 'system';
    } catch {
      return 'system';
    }
  }

  private writeStoredPreference(preference: ThemePreference): void {
    try {
      const storage = this.document.defaultView?.localStorage;
      if (preference === 'system') {
        storage?.removeItem(THEME_STORAGE_KEY);
      } else {
        storage?.setItem(THEME_STORAGE_KEY, preference);
      }
    } catch {
      // Storage can be unavailable (private mode, blocked site data); the
      // preference then lasts for the session only.
    }
  }
}
