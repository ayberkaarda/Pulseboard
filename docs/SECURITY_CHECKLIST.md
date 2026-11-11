# Security checklist (OWASP ASVS 4.0.3, Level 1)

This checklist walks through every Level 1 requirement of the [OWASP Application Security Verification Standard 4.0.3](https://owasp.org/www-project-application-security-verification-standard/) (128 requirements) and states how Pulseboard stands against it. Each status was set by reading the code or configuration named in the row; a test is named where one exists. It is a self-assessment of a portfolio project, not an audit or a certification.

Pulseboard runs only as a local Docker Compose stack (no domain, no TLS, no hosting), so every status describes that setup. Requirements that only make sense in a deployment are marked `NOT DONE` with the reason, not hidden.

Statuses:

- **DONE**: implemented and verified in code; tests are named where they exist.
- **PARTIAL**: implemented in part, or implemented with a stated weakness.
- **NOT DONE**: not implemented.
- **NOT APPLICABLE**: the requirement does not apply because the feature does not exist.

## Summary

| Chapter | DONE | PARTIAL | NOT DONE | NOT APPLICABLE | Total |
|---|---|---|---|---|---|
| V2 Authentication | 10 | 1 | 5 | 11 | 27 |
| V3 Session Management | 4 | 3 | 0 | 5 | 12 |
| V4 Access Control | 6 | 0 | 1 | 1 | 8 |
| V5 Validation, Sanitization and Encoding | 14 | 2 | 0 | 11 | 27 |
| V6 Stored Cryptography | 0 | 0 | 0 | 1 | 1 |
| V7 Error Handling and Logging | 1 | 2 | 0 | 0 | 3 |
| V8 Data Protection | 2 | 3 | 2 | 0 | 7 |
| V9 Communication | 0 | 0 | 3 | 0 | 3 |
| V10 Malicious Code | 1 | 0 | 0 | 2 | 3 |
| V11 Business Logic | 1 | 2 | 0 | 2 | 5 |
| V12 Files and Resources | 0 | 2 | 0 | 9 | 11 |
| V13 API and Web Service | 2 | 2 | 0 | 2 | 6 |
| V14 Configuration | 9 | 3 | 2 | 1 | 15 |
| **Total** | **50** | **20** | **13** | **45** | **128** |

ASVS 4.0.3 has no Level 1 requirement in chapter V1 (Architecture), so that chapter has no rows; architecture-level decisions are in [ARCHITECTURE.md](ARCHITECTURE.md) and [ADR/](ADR/README.md). Chapter V6 has one Level 1 requirement and is included for completeness.

Known gaps and their rationale are collected in [Known gaps and rationale](#known-gaps-and-rationale); accepted behaviours are listed in [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).

## V2 Authentication

| ID | Requirement | Status | Justification |
|---|---|---|---|
| 2.1.1 | Passwords at least 12 characters (after collapsing multiple spaces) | PARTIAL | `AuthDtos.RegisterRequest` requires `@Size(min = 12)` on the raw string. Runs of spaces are not collapsed first, so a password of 12 spaces is accepted. |
| 2.1.2 | Passwords of 64 characters allowed, more than 128 denied | DONE | `@Size(max = 128)` on register and login. `LoginAttackTest.oversizedPasswordIsRejectedBeforeHashing`. |
| 2.1.3 | No password truncation | DONE | The password is passed unchanged to `Argon2PasswordEncoder` (`JwtConfiguration`); there is no trimming in `AuthService`. |
| 2.1.4 | Any printable Unicode character allowed | DONE | No character-class restriction on the password field. No dedicated test. |
| 2.1.5 | Users can change their password | NOT DONE | There is no change-password endpoint or page. |
| 2.1.6 | Password change needs current and new password | NOT DONE | Same: the feature does not exist. |
| 2.1.7 | Passwords checked against breached or common passwords | NOT DONE | Only the length rule is applied. No local list, no external service. |
| 2.1.8 | Password strength meter | NOT DONE | The register page has no meter (`apps/web/src/app/features/auth/register.page.html`). |
| 2.1.9 | No composition rules | DONE | Length is the only rule. |
| 2.1.10 | No periodic rotation or history | DONE | Neither exists. |
| 2.1.11 | Paste, browser helpers and password managers allowed | DONE | The login and register pages contain no paste handlers or autocomplete blocking. |
| 2.1.12 | User can reveal the typed password | DONE | `passwordVisible` toggle on `login.page.html` and `register.page.html`. |
| 2.2.1 | Anti-automation against credential stuffing and brute force | DONE | Redis token buckets: 30 requests/min per client address on all auth endpoints, 10 login attempts per 15 min per tenant slug and email, checked before password hashing (`AuthService`, `RedisTokenBucketRateLimiter`, `application.yml` `pulseboard.rate-limit.auth`); same-time-cost for unknown users (`timingEqualizationHash`). Tests: `AuthThrottlingIntegrationTest`, `AuthClientRateLimitIntegrationTest`, `LoginAttackTest`. No CAPTCHA, no breached-password blocking. The per-account bucket also lets an attacker block a victim's logins for up to 15 minutes. |
| 2.2.2 | Weak authenticators (SMS, e-mail) only for secondary verification | NOT APPLICABLE | Passwords are the only authenticator; there is no second factor. |
| 2.2.3 | Notifications after changes of authentication details or risky logins | NOT DONE | The application sends no e-mail or push notification of any kind. |
| 2.3.1 | System-generated initial passwords or codes are random and expire | NOT APPLICABLE | Users choose their password at registration; the system generates none. |
| 2.5.1 | Initial activation or recovery secret not sent in clear text | NOT APPLICABLE | There is no activation or recovery flow. |
| 2.5.2 | No password hints or secret questions | DONE | None exist. |
| 2.5.3 | Recovery does not reveal the current password | NOT APPLICABLE | No recovery flow. Passwords are stored as Argon2id hashes and cannot be revealed. |
| 2.5.4 | No shared or default accounts | DONE | No migration or seed inserts a user; accounts exist only after self-registration (`db/migration/V1` to `V8`). |
| 2.5.5 | User notified when an authentication factor changes | NOT APPLICABLE | No factor can be changed (see 2.1.5). |
| 2.5.6 | Recovery uses a secure mechanism | NOT APPLICABLE | There is no recovery path at all; a lost password means a lost account (see gaps). |
| 2.7.1 | No clear-text out-of-band authenticators offered by default | NOT APPLICABLE | No out-of-band authentication. |
| 2.7.2 | Out-of-band codes expire after 10 minutes | NOT APPLICABLE | Same. |
| 2.7.3 | Out-of-band codes are single use | NOT APPLICABLE | Same. |
| 2.7.4 | Out-of-band channel is secure and independent | NOT APPLICABLE | Same. |
| 2.8.1 | One-time passwords have a lifetime | NOT APPLICABLE | No OTP. |

## V3 Session Management

Sessions are stateless: a 15-minute HS256 access token (JWT) kept in memory by the web app, and an opaque refresh token. There are no cookies.

| ID | Requirement | Status | Justification |
|---|---|---|---|
| 3.1.1 | Session tokens never in URL parameters | PARTIAL | Access and refresh tokens are sent in headers and bodies only; an access token in the query string is rejected (`StreamTicketIntegrationTest.anAccessTokenInTheQueryStringIsNotAccepted`). The one-time stream ticket does travel in the query string (30 s, single use, masked in logs); see [ADR 0004](ADR/0004-sse-over-websocket.md). |
| 3.2.1 | New session token on authentication | DONE | Every login and registration creates a new refresh-token family and a new access token (`AuthService.issueTokens`). |
| 3.2.2 | Session tokens have at least 64 bits of entropy | DONE | Refresh tokens are 32 bytes from `SecureRandom` (`SecureTokens`); access tokens are signed JWTs. |
| 3.2.3 | Tokens stored in the browser by secure means | DONE | The access token lives in memory only; the refresh token is in `sessionStorage` (dies with the tab) (`RefreshTokenStorage`). The weakness of `sessionStorage` is noted at 8.2.2. |
| 3.3.1 | Logout and expiry invalidate the session | PARTIAL | Logout revokes the whole refresh-token family and the refresh token is single use with reuse detection (`LogoutAttackTest`, `RefreshTokenAttackTest`). A stateless access token stays valid until it expires, at most 15 minutes, because there is no deny list. |
| 3.3.2 | Periodic re-authentication | DONE | Refresh tokens last 7 days from last use and a session ends at most 30 days after login (`REFRESH_FAMILY_TTL`); rotation never extends it (`LogoutAttackTest.rotationNeverExtendsASessionBeyondItsAbsoluteEnd`, `refreshAfterTheAbsoluteEndFailsAndRevokesTheFamily`). |
| 3.4.1 | Cookie sessions: `Secure` attribute | NOT APPLICABLE | No cookies are set. |
| 3.4.2 | Cookie sessions: `HttpOnly` attribute | NOT APPLICABLE | Same. |
| 3.4.3 | Cookie sessions: `SameSite` attribute | NOT APPLICABLE | Same. |
| 3.4.4 | Cookie sessions: `__Host-` prefix | NOT APPLICABLE | Same. |
| 3.4.5 | Cookie sessions: precise `path` | NOT APPLICABLE | Same. |
| 3.7.1 | Full session or re-authentication before sensitive transactions | PARTIAL | Every sensitive route requires a valid access token and a role (`SecurityConfiguration`). There is no step-up re-authentication, for example before creating an API key or deleting an alert rule. |

## V4 Access Control

| ID | Requirement | Status | Justification |
|---|---|---|---|
| 4.1.1 | Access control enforced on a trusted service layer | DONE | Authorization rules live in `SecurityConfiguration` on the server (roles OWNER, ADMIN, VIEWER, API_KEY, plus the ticket and internal chains); `anyRequest().authenticated()` is the default. Rules are not bound to an HTTP method where HEAD or other verbs could slip through. `RoleAuthorizationIntegrationTest`, `HttpSurfaceAttackTest.pathVariantsNeverBypassAuthentication`. |
| 4.1.2 | Attributes used by access control cannot be manipulated by users | DONE | Tenant and role come from the signed JWT or the API key row, never from the request (`TenantFilter`). `MassAssignmentAttackTest`, `JwtTamperingAttackTest` (tenant swap, role escalation, wrong key, `alg: none`). |
| 4.1.3 | Least privilege | DONE | VIEWER reads, ADMIN and OWNER write, API keys can only ingest and read the catalogue, internal endpoints accept only the HMAC. Database roles of `analytics` are restricted as in [ADR 0003](ADR/0003-analytics-role-separation.md). `RoleAuthorizationIntegrationTest`, `InternalEndpointSecurityIntegrationTest`. |
| 4.1.5 | Access controls fail securely | DONE | Unknown routes need authentication; the rate limiter and ticket store fail closed (503) when Redis is down (`AuthRateLimitOutageIntegrationTest`, `StreamTicketOutageIntegrationTest`, `AlertResilienceIntegrationTest`); a catch-all handler returns a generic 500. |
| 4.2.1 | Protection against IDOR | DONE | Every tenant-owned query carries the tenant id (ArchUnit rules in `ArchitectureTest`), composite foreign keys pin child rows to their tenant (V3, V5, V8), and a foreign id answers 404. `CrossTenantIsolationIntegrationTest`, `CrossTenantIdorAttackTest`, `IngestTenantIsolationAttackTest`. |
| 4.2.2 | Anti-CSRF for authenticated functionality | NOT APPLICABLE | Authentication is a bearer header, not an ambient credential, so a cross-site request carries none; CSRF protection is switched off on purpose in `SecurityConfiguration`. |
| 4.3.1 | MFA for administrative interfaces | NOT DONE | There is no separate admin interface; OWNER and ADMIN users sign in with a password only, and MFA does not exist. |
| 4.3.2 | Directory browsing disabled, no metadata files exposed | DONE | nginx serves only the Angular build output copied into the image (`apps/web/Dockerfile`) and `autoindex` is not enabled (`apps/web/nginx.conf`). No test; checked by reading the configuration. |

## V5 Validation, Sanitization and Encoding

| ID | Requirement | Status | Justification |
|---|---|---|---|
| 5.1.1 | Defence against HTTP parameter pollution | PARTIAL | Credential-bearing inputs reject duplicates: a repeated `ticket` parameter (`StreamTicketIntegrationTest.aRepeatedTicketParameterIsRefusedWithoutUsingEitherTicket`) and repeated `X-Internal-Signature` headers (`InternalSignatureFilter`, `test_duplicate_signature_headers_are_unauthorized_in_both_orders`). Other parameters follow the framework defaults. |
| 5.1.2 | Protection against mass assignment | DONE | Requests bind to dedicated DTO records without tenant, role or id fields; tenant and role are set server-side. `MassAssignmentAttackTest`. |
| 5.1.3 | Positive (allow-list) input validation | DONE | Bean Validation patterns and sizes (`AuthDtos`, `CatalogDtos`, `AlertDtos`), enums, JSON Schema for ingest and notifications, database `CHECK` constraints (V1 to V8). `HttpSurfaceAttackTest.oversizedFieldsAreRejectedByValidation`. |
| 5.1.4 | Structured data typed and validated against a schema | DONE | `metric-batch.schema.json` and `anomaly-detected.schema.json` (`JsonSchemaMetricBatchParser`, `JsonSchemaAnomalyNotificationParser`); Pydantic with `extra="forbid"` in `analytics`. `MetricBatchSchemaContractTest`, `AnomalyDetectedSchemaContractTest`, `test_validation.py`. |
| 5.1.5 | Redirects only to allowed destinations | DONE | The server issues no redirects and webhook redirects are never followed. In the browser, `?returnUrl=` is reduced to paths under `/app` (`safeReturnUrl`; `auth.guards.spec.ts`). |
| 5.2.1 | Untrusted HTML from rich editors is sanitized | NOT APPLICABLE | No rich text or HTML input exists. |
| 5.2.2 | Unstructured data is sanitized (characters, length) | DONE | All text fields have length limits and name formats (`CHECK` constraints, DTO sizes); values written to the `EMAIL_LOG` line are stripped of control characters and quotes (`AlertDeliveryService.clean`, `AlertDeliveryIntegrationTest.anEmailLogRuleWritesOneStructuredLineWithoutInjectableText`). |
| 5.2.3 | Input passed to mail systems is sanitized | NOT APPLICABLE | No mail is sent; `EMAIL_LOG` only writes a log line. |
| 5.2.4 | No `eval()` or dynamic code execution | DONE | No `eval`, `new Function` or dynamic class loading in `api-core` or `web` sources. `analytics` evaluates fixed Redis Lua scripts only (`locks.py`), never built from input. |
| 5.2.5 | Protection against template injection | NOT APPLICABLE | No server-side templating. The nginx `sub_filter` replaces a fixed placeholder with the request id. |
| 5.2.6 | Protection against SSRF | DONE | The only user-supplied destination is the webhook URL: https only, DNS names only, port allow-list, every resolved address must be public, connection pinned to the checked address, no redirects, checked on save and on every delivery. [ADR 0005](ADR/0005-webhook-ssrf-and-outbox.md). `WebhookTargetGuardTest`, `ApacheWebhookSenderTest`, `AlertDeliveryIntegrationTest`. A domain allow list is not possible for tenant-chosen endpoints (see 12.6.1). |
| 5.2.7 | User-supplied SVG is sanitized | NOT APPLICABLE | No user-supplied graphics. |
| 5.2.8 | User-supplied scriptable templates are sanitized | NOT APPLICABLE | None accepted. |
| 5.3.1 | Output encoding suited to the context | DONE | Angular templates escape by context; the source contains no `innerHTML` and no `bypassSecurityTrust*`. API output is JSON. |
| 5.3.2 | Output encoding preserves the character set | DONE | JSON is UTF-8 end to end and nothing re-encodes values. No dedicated test. |
| 5.3.3 | Context-aware escaping against reflected, stored and DOM XSS | DONE | Angular auto-escaping plus a CSP with `script-src 'self'` and no inline scripts (`apps/web/nginx/security-headers.conf`). The CSP is checked by reading the configuration; no automated test covers the headers served by nginx. |
| 5.3.4 | Parameterized queries | DONE | Spring Data JPA with bound parameters, `JdbcTemplate` with `?` placeholders, SQLAlchemy `text()` with bind parameters. The few concatenated statements join constant fragments only (`JdbcAnomalyRepository`). |
| 5.3.5 | Context-specific encoding where parameterization is impossible | NOT APPLICABLE | No such case: no dynamic identifiers are built from input. |
| 5.3.6 | Protection against JSON injection and JSON eval | DONE | Jackson and `JSON.parse` only (`RefreshTokenStorage`); no `eval`. |
| 5.3.7 | Protection against LDAP injection | NOT APPLICABLE | No LDAP. |
| 5.3.8 | Protection against OS command injection | NOT APPLICABLE | No source file starts processes or shell commands. |
| 5.3.9 | Protection against LFI and RFI | NOT APPLICABLE | No file path or include is derived from input. |
| 5.3.10 | Protection against XPath and XML injection | NOT APPLICABLE | No XPath or XML processing. |
| 5.5.1 | Serialized objects have integrity checks or encryption | PARTIAL | No native object serialization is used (Redis stores strings and JSON, PostgreSQL stores `jsonb`), but those values are not signed. Redis requires a password and sits on the internal Compose network. |
| 5.5.2 | XML parsers restricted against XXE | NOT APPLICABLE | The application parses no XML. |
| 5.5.3 | Deserialization of untrusted data avoided or protected | DONE | JSON only, no polymorphic default typing (`activateDefaultTyping` is not used), no `pickle`. Model parameters are stored as `jsonb`, not as serialized models. |
| 5.5.4 | `JSON.parse` for JSON in the browser | DONE | No `eval` anywhere in `apps/web/src`. |

## V6 Stored Cryptography

| ID | Requirement | Status | Justification |
|---|---|---|---|
| 6.2.1 | Cryptographic modules fail securely, no padding oracle | NOT APPLICABLE | The application encrypts nothing. Signature and hash checks (JWT, HMAC) reject on any mismatch and report a generic 401. |

## V7 Error Handling and Logging

| ID | Requirement | Status | Justification |
|---|---|---|---|
| 7.1.1 | No credentials or payment details in logs; tokens only hashed | DONE | `SecretRedactingMessageConverter` masks `ticket`, `access_token`, token and JWT fields in every message (`SecretRedactingMessageConverterTest`); passwords are never logged; the refresh token and its hash are absent from responses and logs (`LogoutAttackTest.neitherResponsesNorLogsContainTheTokenOrItsHash`); signing secrets are excluded from `toString` (`JwtProperties`) (`AlertPropertiesTest.theSecretIsNotPrinted`, `test_repr_never_contains_secrets_or_passwords`). Exception stack traces are not rewritten by the masking converter. |
| 7.1.2 | No other sensitive data in logs | PARTIAL | Webhook URLs and receiver responses are never logged in full (`AlertDeliveryIntegrationTest.logsAndStoredErrorsNameNeitherTheTargetNorTheSecretNorTheReceiversText`) and registration logs ids only. The `EMAIL_LOG` channel writes the recipient label the user entered into the log by design. There is no written data-handling policy. |
| 7.4.1 | Generic message for unexpected errors, optionally with an ID | PARTIAL | `server.error.include-stacktrace` and `include-message` are `never`, a catch-all `ProblemDetailsExceptionHandler` answers 500 with "An unexpected error occurred", and malformed bodies and conflicts give generic problem documents (`HttpSurfaceAttackTest`). There is no unique error id in the response to correlate with the log. |

## V8 Data Protection

| ID | Requirement | Status | Justification |
|---|---|---|---|
| 8.2.1 | Anti-caching headers for sensitive data | DONE | Spring Security's default headers (`Cache-Control: no-cache, no-store`) are active on API responses and pass through nginx (`HttpSurfaceAttackTest.securityHeadersArePresentOnApiResponses`, `RefreshTokenAttackTest.refreshTokenResponsesAreNotCacheable`, `StreamTicketIntegrationTest.theIssuingResponseIsNeverCachedAndNamesNoOtherUser`). `index.html` is `no-cache`. |
| 8.2.2 | Browser storage holds no sensitive data | PARTIAL | The access token is never stored. The refresh token is in `sessionStorage`, readable by any script that runs in the page; the CSP is the mitigation. |
| 8.2.3 | Authenticated data cleared from the client after the session ends | DONE | Logout and a rejected refresh run `SessionLifecycle.clear()`, which resets every store and closes the stream (`session-lifecycle.ts`, `session.store.spec.ts`). Open dialogs are closed through `shared/overlay/overlay-session.ts`, which was added late (see the notes at the end). |
| 8.3.1 | Sensitive data not in the query string | PARTIAL | Only the stream ticket (see 3.1.1). Credentials, API keys and passwords travel in headers or bodies. |
| 8.3.2 | Users can export or delete their data | NOT DONE | There is no account deletion or export. Deleting a tenant is not exposed. |
| 8.3.3 | Clear language on personal data use and consent | NOT DONE | No privacy notice or consent step. The application stores an e-mail address and a password hash per user. |
| 8.3.4 | Sensitive data identified, with a handling policy | PARTIAL | Handled by design: passwords as Argon2id, refresh tokens and API keys only as SHA-256 hashes, tickets only as hashes, webhook targets marked sensitive in the schema (`COMMENT ON COLUMN alert_rule.target`), secrets only from the environment. There is no written policy beyond this checklist. |

## V9 Communication

| ID | Requirement | Status | Justification |
|---|---|---|---|
| 9.1.1 | TLS for all client connectivity | NOT DONE | Compose publishes the `web` container on plain HTTP (`WEB_PORT`, default 8081); no certificate is configured and there is no redirect. By decision no domain or hosting exists. Traffic between containers (including to PostgreSQL and Redis) is also unencrypted. Outbound alert webhooks require `https`. |
| 9.1.2 | Only strong cipher suites enabled | NOT DONE | No TLS endpoint exists in the repository. |
| 9.1.3 | Only TLS 1.2 and 1.3 enabled | NOT DONE | Same. |

## V10 Malicious Code

| ID | Requirement | Status | Justification |
|---|---|---|---|
| 10.3.1 | Auto-update only over secure, signed channels | NOT APPLICABLE | No auto-update feature. |
| 10.3.2 | Integrity protection; no code from untrusted sources | DONE | The web bundle and fonts are served from the same origin (`@fontsource-variable/instrument-sans` is bundled) and the CSP allows scripts only from `'self'`. Dependencies are resolved from lock files (`package-lock.json`, `uv.lock`). Build outputs and images are not signed. |
| 10.3.3 | Protection against subdomain takeover | NOT APPLICABLE | No DNS entries or subdomains. |

## V11 Business Logic

| ID | Requirement | Status | Justification |
|---|---|---|---|
| 11.1.1 | Flows are processed in sequential order | NOT APPLICABLE | There are no multi-step business transactions. The ordered pieces that exist are enforced on the server: a ticket must be issued before a stream, and a rotated refresh token cannot be reused. |
| 11.1.2 | Flows complete in realistic human time | NOT APPLICABLE | No such flow. |
| 11.1.3 | Limits on business actions, enforced per user | DONE | Per-tenant ingest batches (FREE 60/min, PRO 600/min), metric queries 120/min per tenant, 50 alert rules per tenant, 600 alert events per hour per tenant, 10 streams per tenant, 30 tickets/min per user, 1000 points per batch. `IngestRateLimitIntegrationTest`, `MetricQueryLimitsIntegrationTest`, `AlertBudgetIntegrationTest`, `AnomalyStreamLimitsIntegrationTest`, `StreamTicketLimitsIntegrationTest`. |
| 11.1.4 | Anti-automation against excessive calls and denial of service | PARTIAL | Rate limits on auth, API keys (including failed-key quotas), ingest, queries, tickets and streams; body size limits (512 KiB), a 5 s statement timeout on metric queries, bounded alert evaluation and delivery pools. Registration of new tenants is limited per client address only (30/min), with no CAPTCHA or e-mail verification. `IngestRateLimitAttackTest`, `ApiKeyAttackTest`, `ApiKeyClientRateLimitIntegrationTest`. |
| 11.1.5 | Business logic limits based on identified threats | PARTIAL | The limits above come from the abuse cases found during development and review. There is no written threat model. |

## V12 Files and Resources

The application accepts no file uploads and reads no user-named files.

| ID | Requirement | Status | Justification |
|---|---|---|---|
| 12.1.1 | Large files are not accepted | NOT APPLICABLE | No file upload. Request bodies are limited (nginx `client_max_body_size 1m` on `/api/`, 512 KiB in `api-core`). |
| 12.3.1 | File names not used directly by the file system | NOT APPLICABLE | No user-supplied file names. |
| 12.3.2 | File name metadata validated against LFI | NOT APPLICABLE | Same. |
| 12.3.3 | File name metadata validated against RFI and SSRF | NOT APPLICABLE | Same; the SSRF surface is the webhook URL (5.2.6). |
| 12.3.4 | Protection against reflective file download | NOT APPLICABLE | No file names in parameters; API responses are JSON. |
| 12.3.5 | File metadata not used in OS commands | NOT APPLICABLE | No file handling. |
| 12.4.1 | Untrusted files stored outside the web root | NOT APPLICABLE | No untrusted files. |
| 12.4.2 | Untrusted files scanned for malware | NOT APPLICABLE | No untrusted files. |
| 12.5.1 | Web tier serves only files with expected extensions | PARTIAL | There is no extension allow list in nginx. The web root holds only the production build output (`apps/web/Dockerfile` copies `dist/web/browser`), and production builds do not emit source maps (`angular.json` enables `sourceMap` for development only). |
| 12.5.2 | Uploaded files are never executed as HTML or JavaScript | NOT APPLICABLE | No uploads. |
| 12.6.1 | Server sends requests only to an allow list of systems | PARTIAL | The server's only user-influenced outbound call is the alert webhook. Protocol and port are allow-listed and every address must be public, but destination hosts cannot be allow-listed because tenants choose them. Compose has no egress filtering or outbound proxy. |

## V13 API and Web Service

| ID | Requirement | Status | Justification |
|---|---|---|---|
| 13.1.1 | All components use the same encodings and parsers | PARTIAL | `/internal` is blocked in nginx on the decoded and normalised path and again protected by the HMAC filter in `api-core`; path-variant tests exist for `api-core` (`HttpSurfaceAttackTest`, `InternalEndpointSecurityIntegrationTest`) and the end-to-end script checks the internal route. The nginx layer itself has no automated test. The webhook URL is parsed once and the connection uses the addresses from that parse. |
| 13.1.3 | API URLs expose no sensitive information | PARTIAL | URLs carry ids only, except the stream ticket (see 3.1.1). |
| 13.2.1 | Enabled HTTP methods are appropriate | DONE | Authorization is per path and verb group, other verbs are rejected (`LogoutAttackTest.getAndOtherVerbsAreNotLogout`, `RoleAuthorizationIntegrationTest`). |
| 13.2.2 | JSON schema validation before accepting input | DONE | JSON Schema for ingest and notify bodies; Bean Validation for the other endpoints; Pydantic for the internal `analytics` endpoint. See 5.1.4. |
| 13.2.3 | Cookie-using REST services are protected from CSRF | NOT APPLICABLE | No cookies. |
| 13.3.1 | XSD validation for XML | NOT APPLICABLE | No SOAP or XML. |

## V14 Configuration

| ID | Requirement | Status | Justification |
|---|---|---|---|
| 14.2.1 | All components up to date, preferably with a dependency checker | PARTIAL | Versions are pinned in `package-lock.json`, `uv.lock` and the Gradle build, and `npm audit --omit=dev` reported 0 vulnerabilities for `apps/web` when this checklist was written. No dependency checker, Dependabot configuration or image scan runs in CI, and `api-core` and `analytics` were not audited. |
| 14.2.2 | Unneeded features and documentation removed | PARTIAL | Actuator is limited to health, info and Prometheus on a separate management port that Compose does not publish. Swagger UI and `/v3/api-docs` stay enabled in `api-core`, and `analytics` keeps FastAPI's `/docs` and `/openapi.json`; neither is routed by the web proxy (only `/api/` is) and both services sit on internal Compose networks. |
| 14.2.3 | Externally hosted assets use SRI | NOT APPLICABLE | No assets are loaded from a CDN or external host. |
| 14.3.2 | Debug modes disabled in production | DONE | `include-stacktrace: never`, DEBUG logging and full health details only in the `local` profile; the `docker` profile logs at INFO and hides health details (`application.yml`, `HttpSurfaceAttackTest.healthAndInfoRevealNoDetails`). The Compose files do not enable FastAPI debug. |
| 14.3.3 | No detailed version information in headers | DONE | nginx runs with `server_tokens off` and does not pass the upstream `Server` header. `analytics` is not reachable from outside the Compose network. No test. |
| 14.4.1 | Every response has a Content-Type with a safe charset | DONE | Problem documents are `application/problem+json`, static files carry the types nginx derives from their extension, and nginx's `/healthz` is `text/plain`. No dedicated test. |
| 14.4.2 | API responses carry a `Content-Disposition: attachment` header | NOT DONE | Not set. Responses are JSON with `X-Content-Type-Options: nosniff`. |
| 14.4.3 | Content Security Policy in place | DONE | `default-src 'none'; script-src 'self'; style-src 'self' 'nonce-...'; connect-src 'self'; frame-ancestors 'none'; object-src 'none'` and more, on static and API locations (`apps/web/nginx/security-headers.conf`). Not covered by an automated test. |
| 14.4.4 | `X-Content-Type-Options: nosniff` on all responses | DONE | Set by nginx on every location and by Spring Security on API responses. |
| 14.4.5 | `Strict-Transport-Security` on all responses | NOT DONE | Not set, because the stack serves plain HTTP locally and HSTS over HTTP has no effect. It belongs to the TLS termination of a real deployment. |
| 14.4.6 | Suitable `Referrer-Policy` | DONE | `strict-origin-when-cross-origin`. |
| 14.4.7 | Embedding in third-party sites prevented | DONE | CSP `frame-ancestors 'none'` and `X-Frame-Options: DENY`. |
| 14.5.1 | Server accepts only the HTTP methods in use, with logging of others | PARTIAL | Unsupported methods get 405 or 401 from Spring MVC and Spring Security. Invalid methods are not logged or alerted on separately. |
| 14.5.2 | Origin header not used for authentication or access control | DONE | `Origin` is not read by any security code. |
| 14.5.3 | Strict CORS allow list | DONE | CORS is not configured, so no `Access-Control-Allow-Origin` is ever sent; the browser app is same-origin through nginx. `HttpSurfaceAttackTest.crossOriginRequestsGetNoCorsGrant`. |

## Known gaps and rationale

| Gap | ASVS | Why it is open |
|---|---|---|
| No TLS, no HSTS | 9.1.1 to 9.1.3, 14.4.5 | The project is a local Compose stack by decision; a certificate and domain would only be configuration of a real deployment. Container-to-container traffic is unencrypted. |
| No MFA | 4.3.1 | Out of scope for the portfolio feature set. OWNER and ADMIN are password-only. |
| No password change, recovery or breach check | 2.1.5 to 2.1.8, 2.5.6 | Account management beyond register, login and logout was not built. A lost password cannot be recovered. |
| No account notifications, data export or deletion, privacy notice | 2.2.3, 8.3.2, 8.3.3 | There is no mail system and no account lifecycle. |
| Access tokens survive logout for up to 15 minutes | 3.3.1 | Stateless JWTs are not on a deny list; the short lifetime bounds the window. |
| Refresh token in `sessionStorage` | 8.2.2 | Needed to survive a reload without cookies. Mitigated by the CSP, scoped to the tab. |
| Stream ticket in the URL | 3.1.1, 8.3.1, 13.1.3 | The browser `EventSource` cannot set headers. Mitigated by a 30 second, single-use, hashed-at-rest ticket and log masking ([ADR 0004](ADR/0004-sse-over-websocket.md)). |
| No dependency checker, image scan or SBOM in CI | 14.2.1 | Not added. A one-off `npm audit` of the web production dependencies was clean. |
| No egress filtering for webhooks | 12.6.1 | The SSRF controls are in the application ([ADR 0005](ADR/0005-webhook-ssrf-and-outbox.md)); a network-level control would be a deployment concern. |
| No write-up of a threat model | 11.1.5 | The abuse cases are covered by the attack tests in `apps/api-core/src/test/java/io/pulseboard/core/attacks/`. |
| No log retention or log shipping policy | (7.x, not Level 1) | Logs go to the container's standard output; nothing is retained, rotated or monitored by the project. |
| No data retention for metric points, anomalies or alert events | (not an ASVS item) | Partitioning makes retention possible but no job exists ([ADR 0002](ADR/0002-postgres-partitioning.md)); see [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md). |
| Swagger UI and FastAPI docs are enabled | 14.2.2 | Reachable only inside the Compose networks. |

The following behaviours are already recorded as accepted limitations and also bear on this checklist: trusted proxy ranges and shared rate-limit buckets behind NAT, the ticket in the URL, the unsigned timestamp on the internal HMAC, at-least-once alert delivery, restricted webhook targets, single signing secret. See [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).

Controls beyond Level 1 that are in place, for context: HMAC-SHA256 authentication of service-to-service calls with a constant-time comparison and a body size limit applied before hashing; least-privilege database roles verified at startup; required secrets of at least 32 characters with no defaults (`JWT_SECRET`, `INTERNAL_SHARED_SECRET`, `ALERT_WEBHOOK_SIGNING_SECRET`), and required database and Redis passwords in Compose; non-root users in the `api-core` and `analytics` images; actuator on a separate port that is not published.

## Notes on how this was checked

Statuses were set by reading the named classes, migrations and configuration. Tests are cited by class or method name; they were not rerun for this document. Several files in the working tree were being edited while this was written (alert delivery, the webhook sender and the web overlay session), so statements about those areas describe the code as read at the time.
