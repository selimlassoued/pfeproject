import { Injectable, signal } from '@angular/core';

export type ThemeName = 'light' | 'dark';

const STORAGE_KEY = 'hireai.theme';
const DEFAULT_THEME: ThemeName = 'dark';

/**
 * Runtime theme switcher. Writes `data-theme` on <html> so global CSS tokens
 * (defined in styles.css) flip atomically. Persists choice to localStorage
 * and respects the OS-level prefers-color-scheme on first visit only.
 *
 * Call `init()` ONCE as early as possible (in main.ts before bootstrap) to
 * avoid a flash of the wrong theme before Angular renders.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly _current = signal<ThemeName>(DEFAULT_THEME);

  /** Current theme as a reactive signal — components can react to it. */
  readonly current = this._current.asReadonly();

  /** Read storage + OS preference and apply. Safe to call multiple times. */
  init(): void {
    const stored = this.readStored();
    const initial: ThemeName = stored ?? this.systemPreference() ?? DEFAULT_THEME;
    this.apply(initial, false);
  }

  /** Switch to a specific theme. */
  setTheme(theme: ThemeName): void {
    this.apply(theme, true);
  }

  /** Flip between light and dark. */
  toggle(): void {
    this.setTheme(this._current() === 'dark' ? 'light' : 'dark');
  }

  /** True when running in light mode — handy for *ngIf in templates. */
  isLight(): boolean { return this._current() === 'light'; }

  /** True when running in dark mode. */
  isDark(): boolean { return this._current() === 'dark'; }

  private apply(theme: ThemeName, persist: boolean): void {
    this._current.set(theme);
    if (typeof document !== 'undefined') {
      document.documentElement.setAttribute('data-theme', theme);
    }
    if (persist) {
      try { localStorage.setItem(STORAGE_KEY, theme); } catch { /* private mode */ }
    }
  }

  private readStored(): ThemeName | null {
    try {
      const v = localStorage.getItem(STORAGE_KEY);
      return v === 'light' || v === 'dark' ? v : null;
    } catch {
      return null;
    }
  }

  private systemPreference(): ThemeName | null {
    if (typeof window === 'undefined' || !window.matchMedia) return null;
    if (window.matchMedia('(prefers-color-scheme: light)').matches) return 'light';
    if (window.matchMedia('(prefers-color-scheme: dark)').matches)  return 'dark';
    return null;
  }
}
