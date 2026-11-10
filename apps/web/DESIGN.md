# Pulseboard design

Pulseboard is a multi-tenant service health and anomaly analytics board. People open it
during calm weeks to check trends, and during incidents to find out what broke, how badly,
and since when. The design is built for the second case and stays pleasant in the first.

This file records the identity decisions and the reasons behind them. Token values live in
`src/styles/_tokens.scss`; the Angular Material theme mapping lives in `src/styles/_theme.scss`.

## 1. Identity: "the instrument"

Pulseboard should feel like a well-made measuring instrument: calibrated, quiet at rest,
precise when something moves. Three rules follow from that.

1. **Color is reserved for signal.** Chrome is graphite ink on porcelain. Hue appears only
   where it carries meaning: health, severity, chart series. A screen with nothing wrong is
   almost colorless, so a problem is visible from across the room.
2. **The brand is the anomaly.** The single brand hue, _flare_ (a hot vermilion), is also the
   anomaly severity ramp. The logo is a chart with one point out of range, marked in flare.
3. **Type does the talking.** One variable family with a width axis gives condensed,
   instrument-label headings and a normal-width reading face without a second typeface.

What it deliberately avoids: purple-to-blue gradients, glass panels, a dark canvas with a
single neon accent, identical rounded cards with soft gray shadows, all-caps eyebrow labels,
and monospace used as decoration.

## 2. Logo and wordmark

- **Mark** (`app-brand-mark`, `public/logo.svg`, `public/favicon.svg`, `public/favicon.ico`):
  a 32px ink tile with rounded corners (8px). Inside, a faint band shows the expected range;
  a trace runs through it, peaks once outside it, and a flare dot with an ink halo sits on
  the peak. It is literally the product's chart grammar (section 7) at 16px.
- **Wordmark**: live text, `pulseboard` in lowercase, Instrument Sans at 640 weight, 78%
  width, -0.015em tracking (`.pb-wordmark`). Lowercase keeps it calm next to condensed
  page titles; it is never drawn as an image, so it inherits theme and scales cleanly.
- **Favicon**: the SVG favicon switches tile and trace colors under
  `prefers-color-scheme: dark`; `favicon.ico` (16/32/48 PNG entries) is the fallback.
- **Motion**: in the rail, the trace draws itself once on load (900ms), then the flare dot
  appears. This is the only unprompted animation in the shell.

## 3. Color

All color tokens are declared once with `light-dark()`; the theme switch changes
`color-scheme` on `<html>` and every token follows. Values below are light / dark.

### Neutrals and ink

| Token                 | Light     | Dark      | Role                                      |
| --------------------- | --------- | --------- | ----------------------------------------- |
| `--pb-canvas`         | `#f2f3f2` | `#0e1113` | Page plane                                |
| `--pb-surface`        | `#fbfbfa` | `#161a1d` | Panels, rail                              |
| `--pb-surface-sunken` | `#e7e9e8` | `#111416` | Wells, inputs                             |
| `--pb-surface-raised` | `#ffffff` | `#1f2428` | Dialogs, menus                            |
| `--pb-ink`            | `#15191c` | `#e9ecec` | Primary text, primary actions, focus ring |
| `--pb-ink-2`          | `#454e55` | `#b3bcc2` | Secondary text                            |
| `--pb-ink-3`          | `#5f6870` | `#8f99a1` | Muted text, axis labels                   |
| `--pb-line`           | `#dcdfde` | `#2a3034` | Decorative hairlines                      |
| `--pb-line-strong`    | `#7d868c` | `#6c767e` | Control boundaries                        |

Neutrals lean slightly cool with very low chroma so they never compete with signal hues.
The accent for interaction is ink itself: primary buttons are solid ink, hover and selection
are ink washes (6% and 9% light, 7% and 11% dark).

### Flare and anomaly severity

Severity (`LOW`, `MEDIUM`, `HIGH`) is ordinal, so it is a **single-hue ramp** stepped in
lightness, not a traffic light. Amber/orange/red triads were tested first and failed
color-vision separation (amber and orange measured ΔE 0.6 to 3.1 under deuteranopia and
protanopia simulation). A one-hue ramp passes the ordinal checks (monotone lightness,
visible step gaps, a single hue) in both modes.

| Severity | Light     | Dark      | Glyph           |
| -------- | --------- | --------- | --------------- |
| Low      | `#e8925e` | `#8f4325` | 1 of 3 bars lit |
| Medium   | `#cf4f1a` | `#e0622a` | 2 of 3 bars lit |
| High     | `#962112` | `#ffa27a` | 3 of 3 bars lit |

Light mode gets darker as severity rises; dark mode gets brighter. In both, higher
severity means more contrast against the surface. `--pb-flare` equals Medium.

### Service health

Health is a state, not an order, so it uses distinct hues **and** distinct shapes.

| State    | Light     | Dark      | Glyph                 |
| -------- | --------- | --------- | --------------------- |
| Healthy  | `#1f8a4c` | `#3fbf72` | Filled circle         |
| Degraded | `#a86a00` | `#e2a63a` | Filled triangle       |
| Down     | `#c8322f` | `#f2615c` | Filled square         |
| Stale    | `#727b82` | `#7c868e` | Ring with clock hands |
| No data  | `#727b82` | `#7c868e` | Dashed hollow ring    |

Stale means no open anomaly but no sample for more than 15 minutes (`STALE_AFTER_MS`): silence is
not health, so such a service is never shown as Healthy. The tile says "Last data 2 hours ago". Stale and No
data share the neutral grey; the clock hands and the label tell them apart. An open anomaly always wins
over stale. Under a truncated anomaly result a service without a loaded anomaly shows No data, not Healthy.

Status colors never stand alone: every state renders glyph + text label. They are never
reused as chart series colors.

### Alert delivery

Delivery of an alert is a state with three outcomes, so it also pairs a shape with a hue and a word.
It reuses the neutral, healthy and down hues of the health table and never appears in charts.

| State   | Hue               | Glyph                | Meaning                                           |
| ------- | ----------------- | -------------------- | ------------------------------------------------- |
| Pending | Neutral (no data) | Pill with three dots | Waiting for delivery or for the next retry.       |
| Sent    | Healthy           | Ring with a check    | The receiver accepted the notification.           |
| Failed  | Down              | Ring with a cross    | Every attempt failed, or the receiver refused it. |

The signal key dialog lists these three beside health and severity.

### Chart series (categorical)

Fixed order, never cycled; a ninth series folds into "Other" or small multiples.

| Slot | Light     | Dark      |
| ---- | --------- | --------- |
| 1    | `#2a78d6` | `#3987e5` |
| 2    | `#eb6834` | `#d95926` |
| 3    | `#1baf7a` | `#199e70` |
| 4    | `#eda100` | `#c98500` |
| 5    | `#e87ba4` | `#d55181` |
| 6    | `#008300` | `#008300` |
| 7    | `#6250d6` | `#9085e9` |
| 8    | `#e34948` | `#e66767` |

Validated against Pulseboard's own surfaces with a palette checker (OKLab ΔE ×100,
adjacent pairs): light worst CVD ΔE 9.1, worst normal-vision ΔE 19.6; dark worst CVD ΔE 8.4,
worst normal-vision ΔE 19.3. All pass. Light slots 3, 4 and 5 sit below 3:1 against the
surface, so charts that use them must show direct labels or offer the table view.
Scatter-like charts where every pair can touch are capped at the first three slots.

### Contrast, measured

WCAG 2.x ratios, computed from the hex values and re-measured in the running app from
computed styles (57 text elements per theme, shell and dialog).

| Pair                     | Light       | Dark        | Requirement |
| ------------------------ | ----------- | ----------- | ----------- |
| Ink on surface           | 17.08       | 14.73       | 4.5 text    |
| Ink-2 on canvas (lowest) | 7.63        | 9.08        | 4.5 text    |
| Ink-3 on surface         | 5.48        | 6.03        | 4.5 text    |
| Ink-3 on sunken          | 4.65        | 6.38        | 4.5 text    |
| Line-strong on surface   | 3.58        | 3.78        | 3.0 UI      |
| Flare on surface         | 4.24        | 4.96        | 3.0 UI      |
| Health colors on surface | ≥4.16       | ≥4.72       | 3.0 UI      |
| Severity High / Medium   | 8.04 / 4.24 | 8.92 / 4.96 | 3.0 UI      |
| Severity Low             | 2.33        | 2.50        | see note    |

Lowest measured text contrast in the running shell: 7.63:1 (light), 9.08:1 (dark).
Severity Low is intentionally the faintest step of an ordinal ramp; the bar count and the
text label carry the level, so color is never the only cue (WCAG 1.4.1, 1.4.11).

## 4. Typography

- **Family**: Instrument Sans (variable, weight 400 to 700, width 75% to 100%), self-hosted
  through `@fontsource-variable/instrument-sans`. The build copies the woff2 files into the
  bundle, so no font request leaves the app's own origin. Latin and Latin Extended subsets
  load on demand via `unicode-range`; `font-display: swap`.
- **Why this face**: it was drawn for precision interfaces, its tabular figures are true
  (verified in the browser: "1111" and "0000" measure the same width with `tnum`), and its
  width axis gives condensed headings without a second family.
- **Fallback**: `ui-sans-serif, system-ui, 'Segoe UI', sans-serif`.

| Step  | Size / line | Use                               | Width |
| ----- | ----------- | --------------------------------- | ----- |
| `xs`  | 12 / 16     | Axis ticks, timestamps            | 100%  |
| `sm`  | 13 / 20     | Dense tables, section labels      | 100%  |
| `md`  | 14 / 20     | Body, controls                    | 100%  |
| `lg`  | 16 / 24     | Page summaries, empty-state body  | 100%  |
| `xl`  | 20 / 28     | Panel titles                      | 88%   |
| `2xl` | 28 / 32     | Mobile page titles, dialog titles | 78%   |
| `3xl` | 40 / 44     | Desktop page titles, hero figures | 78%   |

Weights: 420 regular, 520 nav, 560 medium, 600 to 640 headings. Labels are sentence case;
no all-caps, no letter-spaced eyebrows. Figures in tables, axes and `<time>` use
`tabular-nums`; large standalone numbers keep proportional figures.

## 5. Space, radius, elevation

- **Space**: 4px base: 4, 8, 12, 16, 24, 32, 48, 64.
- **Radius encodes hierarchy**: 2px for marks and tiny chips, 6px for controls (buttons,
  nav items, toggles), 10px for panels, 14px for overlays. Pills are reserved for status
  chips. One radius on everything is avoided on purpose.
- **Elevation**: surfaces are flat and separated by hairlines. Only overlays (dialogs, menus,
  the mobile drawer) cast a shadow: a tight 1px contact shadow plus a soft 32px ambient one.

## 6. Motion

- **Durations**: 120ms state changes, 200ms enter, 320ms panels, 900ms the one brand trace.
- **Easing**: `cubic-bezier(0.2, 0, 0, 1)` for entrances, `cubic-bezier(0.3, 0, 1, 1)` for
  exits.
- **What moves**: things the person caused (drawer, dialog, selection) and, in Phase 4,
  new data arriving (a new anomaly row slides in once; a chart extends its line). Nothing
  loops forever. Live status is shown by a timestamp, not a pulsing dot.
- **Reduced motion**: duration tokens collapse to 0 and a global rule stops animations and
  transitions; the logo renders finished.

## 7. Charts and anomaly marks

Charts follow one grammar so every view reads the same way.

- **Observed**: a 1.5 to 2px line in the series color, rounded joins. Single-metric charts
  use slot 1.
- **Expected range**: a neutral band (`--pb-chart-expected`, ink at 10% / 14%) behind the
  line. It is gray on purpose: the range is context, not signal.
- **Anomaly**: a hollow ring (8px minimum, 1.5px stroke in the severity color, filled with
  the surface color, 2px surface halo) at the point that left the range. A sustained anomaly
  also shades its time window in the severity color at low alpha.
- **Chrome**: hairline grid (`--pb-chart-grid`), quieter axis lines, `xs` tabular tick labels
  in `--pb-ink-3`. One y-axis per chart; two measures become two charts.
- **Interaction**: crosshair with a tooltip listing values in ink (never in series color),
  legend present for two or more series, a table view for every chart.
- **ECharts mapping (Phase 4)**: read tokens at runtime with `getComputedStyle` and rebuild
  the chart options when the theme changes, so charts never hard-code hex values.

## 8. Icons

A small in-house set (`app-icon`), drawn on a 24px grid with a 1.6px stroke, round caps and
joins, and no fills except where fill carries meaning (the half-filled "system theme"
circle). Shapes reuse the product vocabulary: tiles for the overview, stacked rows for
services, a trace with a ring for anomalies, sliders for rules. Icons are always
`aria-hidden`; the adjacent label or the button's `aria-label` names the action. Using an
inline set avoids an icon font and its external request.

## 9. Accessibility commitments

- WCAG 2.2 AA contrast (section 3), measured, not estimated.
- Every interactive element is a native `button` or link; focus is a 2px ink ring with a 2px
  offset, visible on every surface; `forced-colors` switches it to `Highlight`.
- A "Skip to content" button appears on first Tab and focuses `<main>`.
- Navigation uses `aria-current="page"`; the theme switch is a labelled radio group; the
  drawer toggle exposes `aria-expanded`; dialogs trap focus and return it on close.
- No meaning by color alone: health has shapes, severity has bar counts, series have direct
  labels or legends.
- Theme: system, light or dark; the explicit choice is stored per browser and wins over the
  OS setting.

## 10. Shell

- **Desktop (≥960px)**: a 248px docked rail with the mark and wordmark, primary navigation
  (Overview, Services, Anomalies, Alert rules), and a footer with the signal key and the
  three-way theme switch. The current page gets an ink wash, heavier weight, and a 3px flare
  marker: the only place flare appears in the chrome besides the logo.
- **Mobile (<960px)**: a sticky 56px top bar with a menu button and the brand; the rail
  becomes an overlay drawer with a scrim.
- **Page header**: condensed 40px title (28px on mobile) and a one-line summary in `lg`.
- **Empty states**: a flat trace ending in an open ring ("waiting for the first point"),
  a heading that says what is missing and a body that says what will make it appear.
- **Signal key dialog**: the legend for health, severity and chart marks, reachable from
  the rail and the overview.

## 11. Screen skeletons (Phase 4)

These describe layout and behavior; they are built on the tokens and components above.

### Sign in

Split canvas. Left (desktop only): the mark at large size over the expected-range band
motif, with one sentence on what Pulseboard does. Right: a single 360px column on surface:
wordmark, "Sign in to your workspace", email, password, a full-width ink "Sign in" button,
inline field errors under each input ("Enter the email you signed up with"), and a single
form-level error for rejected credentials that does not reveal which field was wrong. Rate
limit responses show the wait time. Mobile collapses to the right column only.

### Overview: service health grid

Header row: page title, a "Last updated" timestamp, and a filter row (health state,
search by service name) in one line. Below, a summary strip of five counts (healthy,
degraded, down, stale, no data) using glyph + number + label. The grid: auto-fill tiles, minimum
220px, 12px gap, sorted by urgency (down, degraded, open anomalies, then name). Each tile:
service name, health glyph + label, p95 latency and error rate in tabular figures, a 24px
sparkline of the last hour with its expected band, and an open-anomaly count with the
highest severity glyph. Whole tile is one link to the service page. Down tiles get a 2px
health-colored left edge; nothing else changes color.

### Service: metric charts

Header: service name, health, a time-range control (presets 15m, 1h, 6h, 24h, 7d and a
custom range) aligned right. Main column: one chart per metric (latency p95, error rate,
throughput), each 240px tall, sharing a synced crosshair across charts. Each chart shows
observed line, expected band and anomaly rings; clicking a ring opens the anomaly detail
in a side panel. A "Table" toggle per chart swaps the plot for the underlying rows.
Right column (≥1280px): recent anomalies for this service, compact.

### Anomaly stream

A live list, newest first, fed by server-sent events. Each row: severity glyph + label,
metric and service, observed value against expected range ("412 ms, expected 120 to 180"),
start time and duration in tabular figures, and status (open or resolved). New rows slide
in once at the top; if the person has scrolled down, a "3 new anomalies" button appears
instead of shifting the list. Filters: severity, service, status. Connection state is
stated in text ("Live" with last-event time, or "Reconnecting, last event 14:02").

### Alert rule editor

A dedicated route (`/app/alerts/rules/new` and `/app/alerts/rules/:id`) with one column of three panels:
what to watch (name, service, metric), alert when (a radio choice between a threshold, with
operator and a value shown in the metric's unit, and an anomaly severity, with the severity
glyphs) and notify (channel, target, cooldown presets plus a seconds field, enabled). The
fields of the condition that is not chosen are removed, not hidden, and a polite status
line says what appeared. The target field changes meaning with the channel. Save is an ink
button labeled "Save rule"; the success toast says "Rule saved". Validation messages sit
under each field and say how to fix the value; server messages replace the client hints.
The live preview of the last 24 hours is not built: the API has no endpoint that replays a
rule. Leaving a form with edits asks first ("Discard your changes?").

## 12. Implementation notes

- Material 3 theme via `mat.theme()` with `theme-type: color-scheme`, then
  `mat.theme-overrides()` maps system roles (primary, surface, outline and the container
  steps) to Pulseboard tokens. Component overrides set button, toggle, dialog and sidenav
  shapes and colors. No prebuilt Material theme is loaded.
- The signal key dialog and Material's dialog machinery load lazily on first open, keeping
  them out of the initial bundle.
- Component styles stay under the 2 kB budget; shared patterns (`.pb-wordmark`,
  `.skip-link`, focus ring) live in `src/styles/_base.scss`.
