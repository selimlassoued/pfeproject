import 'zone.js';

import { bootstrapApplication } from '@angular/platform-browser';
import { App } from './app/app';
import { browserConfig } from './app/app.config.browser';

bootstrapApplication(App, browserConfig).catch(console.error);