# Design: token-tollgate dashboard

대시보드의 잠긴 디자인 시스템이다. 화면을 새로 만들거나 고칠 때 이 파일을 먼저 읽고, 모든 화면이 같은 시스템을 쓴다. 바꿀 때는 의도를 갖고 이 파일부터 고친다.
(아래 형식은 Hallmark 디자인 스킬이 읽는 형식이라 항목 이름을 영어로 둔다.)

The locked system. Every Hallmark run reads this first and pages share it. Change it on purpose; the file is the rule.

## System
- Genre: modern-minimal
- Shape of the first page: app screen (the overview, recomposed inside the existing side rail)
- Theme: Cobalt
- Axes: light/grotesk-sans/cool · posture: restrained
- Modes: light and dark from one token set; dark follows the OS setting and is derived, not inverted (same hue, paper at L 16.5, elevation lighter, shadows replaced by edges)
- Language: Korean UI. Familjen Grotesk and IBM Plex Sans carry Latin and figures; IBM Plex Sans KR follows each in the stack for Hangul. Text breaks between words (`word-break: keep-all`).

## Tokens
`src/tokens.css` is the source of truth. The roles (light values):
```css
:root {
  --color-paper: oklch(98.6% 0.005 250);  --color-paper-2: oklch(96.2% 0.008 252);  --color-paper-3: oklch(93% 0.011 252);
  --color-ink: oklch(21% 0.028 262);  --color-ink-2: oklch(36% 0.022 260);  --color-muted: oklch(50% 0.018 258);
  --color-rule: oklch(90% 0.009 252);  --color-rule-2: oklch(83% 0.012 252);
  --color-accent: oklch(52% 0.19 258);  --color-accent-ink: oklch(98.6% 0.005 250);  --color-focus: oklch(52% 0.19 258);
  --color-accent-deep: oklch(43% 0.2 262);  --color-accent-wash: oklch(95% 0.028 256);
  --color-error: oklch(52% 0.19 27);  --color-success: oklch(46% 0.12 150);  --color-warning: oklch(48% 0.1 75);
  --font-display: "Familjen Grotesk", "IBM Plex Sans KR", system-ui, sans-serif;
  --font-body: "IBM Plex Sans", "IBM Plex Sans KR", system-ui, sans-serif;
  --font-outlier: "JetBrains Mono", "IBM Plex Sans KR", ui-monospace, monospace;
  --radius-card: 8px;  --radius-input: 6px;  --rule-hair: 1px;  --shadow-card: 0 1px 2px oklch(21% 0.028 262 / 0.06);
  --control: 40px;  --control-s: 32px;  --row: 40px;  --text-figure: 2.986rem;
}
```
The spacing scale, the type scale (a fixed 1.2 ratio from 16px), the easings and the durations are the scaffold's and live in `tokens.css`.

## Surfaces
- The work sits on paper; the side rail and the filter row are paper-2 (the second neutral). No white card on a tinted page.
- Containment is a 1px rule. Panels, the modal and the login card are cut on two opposite corners (`corner-shape: bevel`, 8px radius as the fallback).
- Tables are open: horizontal hairlines only, heads in the label voice, numbers in tabular figures.
- Mono is for what a machine wrote (model ids, key prefixes, request ids, chart axis values), never for a label.
- Status is a word in its color, never a filled chip. The accent marks the primary action and the one series a chart argues from.
- The accent wash under accent ink means "chosen" and nothing else: the rail's current screen, the selected project, the pressed key in a choice. Never use it to rank or decorate a row.
- The side rail stays pinned above 60rem. Under 40rem a wide table pins the column that names each row and shows a scroll shadow on its right edge.

## Controls
- Primary: a cobalt key (accent fill on a 2px bottom edge) · 6px radius · 40px
- Secondary: a neutral key face on the same edge · same radius and height; a 32px size for rows and toolbars
- A choice between a few: keys on a sunken tray, the chosen one pressed in
- Inputs: 1px muted border that never changes width, an inset focus ring, 40px (32px in the filter row)

## Motion
- Character: still. State only: a key lifts 1px on hover and sinks when pressed, at 120ms. No reveal. Under reduced motion everything ends at once.

## Exports
`src/tokens.css` in this project. Tailwind, DTCG or shadcn blocks are added here on request.
