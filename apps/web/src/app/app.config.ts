import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideEchartsCore } from 'ngx-echarts';

import { routes } from './app.routes';
import { authInterceptor, errorInterceptor, refreshInterceptor } from './core/http/interceptors';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    // Order matters, outermost first: errors are decoded last, and a request replayed after a
    // refresh passes through the auth interceptor again so it carries the new token.
    provideHttpClient(
      withFetch(),
      withInterceptors([errorInterceptor, refreshInterceptor, authInterceptor]),
    ),
    provideEchartsCore({ echarts: () => import('echarts') }),
  ],
};
