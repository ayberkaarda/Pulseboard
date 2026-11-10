# Pulseboard Web

Angular 22 single-page application for Pulseboard (standalone components, signals, zoneless change detection, strict TypeScript).

Visual identity, tokens, theming and screen layouts are documented in [DESIGN.md](DESIGN.md).

## Requirements

- Node.js 22.22.3+, 24.15+ or 26 (LTS; the container image uses 26)

## Scripts

| Command                                   | Purpose                                                                                                                                |
| ----------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `npm start`                               | Dev server on `http://localhost:4200`; `/api` is proxied to `API_PROXY_TARGET` (default `http://localhost:8080`, see `proxy.conf.js`). |
| `npm run lint`                            | ESLint (angular-eslint, typescript-eslint, Prettier-compatible).                                                                       |
| `npm test`                                | Jest via `@angular-builders/jest`.                                                                                                     |
| `npm run build`                           | Production build into `dist/web/browser`.                                                                                              |
| `npm run format` / `npm run format:check` | Prettier write / check.                                                                                                                |

## Container image

`Dockerfile` builds the app with `node:26.13.0-alpine` and serves it from `nginx:1.30.6-alpine`.
`nginx.conf` provides SPA fallback to `index.html`, proxies `/api/` to `api-core:8080`
(unbuffered for Server-Sent Events) and exposes `GET /healthz` for container health checks.

```bash
docker build -t pulseboard-web .
```
