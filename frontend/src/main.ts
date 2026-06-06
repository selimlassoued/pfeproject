import 'zone.js';

import { bootstrapApplication } from '@angular/platform-browser';
import { App } from './app/app';
import { browserConfig } from './app/app.config.browser';

// Apply persisted/preferred theme BEFORE Angular renders so users never see a
// flash of the wrong theme. Reads localStorage + prefers-color-scheme; falls
// back to dark. Mirrors what ThemeService.init() does, just inline.
(() => {
  try {
    const stored = localStorage.getItem('hireai.theme');
    const sys =
      typeof window !== 'undefined' && window.matchMedia
        ? window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
        : 'dark';
    const theme = stored === 'light' || stored === 'dark' ? stored : sys;
    document.documentElement.setAttribute('data-theme', theme);
  } catch {
    document.documentElement.setAttribute('data-theme', 'dark');
  }
})();

bootstrapApplication(App, browserConfig).catch(console.error);
