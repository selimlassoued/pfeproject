import { RenderMode, ServerRoute } from '@angular/ssr';

export const serverRoutes: ServerRoute[] = [
  {
    path: '**',
    // Avoid prerendering parameterized routes like `jobs/:id` unless you also
    // provide `getPrerenderParams` for them.
    renderMode: RenderMode.Server
  }
];
