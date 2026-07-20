---
name: Technical Precision
colors:
  surface: '#13121b'
  surface-dim: '#13121b'
  surface-bright: '#393842'
  surface-container-lowest: '#0e0d16'
  surface-container-low: '#1b1b24'
  surface-container: '#1f1f28'
  surface-container-high: '#2a2933'
  surface-container-highest: '#35343e'
  on-surface: '#e4e1ee'
  on-surface-variant: '#c7c4d8'
  inverse-surface: '#e4e1ee'
  inverse-on-surface: '#302f39'
  outline: '#918fa1'
  outline-variant: '#464555'
  surface-tint: '#c3c0ff'
  primary: '#c3c0ff'
  on-primary: '#1d00a5'
  primary-container: '#4f46e5'
  on-primary-container: '#dad7ff'
  inverse-primary: '#4d44e3'
  secondary: '#b7c8e1'
  on-secondary: '#213145'
  secondary-container: '#3a4a5f'
  on-secondary-container: '#a9bad3'
  tertiary: '#ffb695'
  on-tertiary: '#571f00'
  tertiary-container: '#a44100'
  on-tertiary-container: '#ffd2be'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#e2dfff'
  primary-fixed-dim: '#c3c0ff'
  on-primary-fixed: '#0f0069'
  on-primary-fixed-variant: '#3323cc'
  secondary-fixed: '#d3e4fe'
  secondary-fixed-dim: '#b7c8e1'
  on-secondary-fixed: '#0b1c30'
  on-secondary-fixed-variant: '#38485d'
  tertiary-fixed: '#ffdbcc'
  tertiary-fixed-dim: '#ffb695'
  on-tertiary-fixed: '#351000'
  on-tertiary-fixed-variant: '#7b2f00'
  background: '#13121b'
  on-background: '#e4e1ee'
  surface-variant: '#35343e'
typography:
  headline-sm:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
  body-base:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  code-base:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 18px
  code-sm:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '400'
    lineHeight: 16px
  label-caps:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: '700'
    lineHeight: 16px
    letterSpacing: 0.05em
rounded:
  sm: 0.125rem
  DEFAULT: 0.25rem
  md: 0.375rem
  lg: 0.5rem
  xl: 0.75rem
  full: 9999px
spacing:
  unit: 4px
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px
  gutter: 12px
  margin: 16px
---

## Brand & Style
The design system is engineered for high-stakes developer security environments. The personality is authoritative, precise, and utilitarian, mirroring the ergonomics of high-end Integrated Development Environments (IDEs). It prioritizes information density over aesthetic flourish, ensuring that security engineers can scan vast amounts of data without visual fatigue.

The design style is **Corporate / Modern** with a lean toward **Minimalism**. It utilizes a "density-first" approach, characterized by tight margins, small but highly legible typography, and a structural layout that favors functionality. Visual hierarchy is established through subtle tonal shifts rather than dramatic shadows or decorative elements. The interface should feel like an extension of the developer's terminal or editor—predictable, fast, and robust.

## Colors
This design system employs a specialized technical palette optimized for both Light and Dark modes.

- **Primary:** An Indigo (#4F46E5) used sparingly for primary actions and active states to maintain focus.
- **Semantic Palette:** High-chroma colors used strictly for status indicators. Red indicates blocked packages or vulnerabilities; Amber indicates warnings or manual reviews; Green indicates clean scans; Blue/Gray indicates general system info.
- **Surface Grays:** A structured hierarchy of grays defines the workspace. In dark mode, deep charcoal and navy-grays (#0F172A) prevent pure-black eye strain. In light mode, crisp whites and cool Gray 50 provide a clinical, clean environment.
- **Interactive States:** Use subtle background shifts (e.g., Gray 700 to Gray 600) rather than high-contrast changes for hover and selection to maintain high density without visual vibration.

## Typography
Typography is the primary tool for hierarchy in this system. 

- **Inter** is the workhorse for the interface, chosen for its exceptional legibility at small sizes. The standard body text is 14px, while metadata and secondary labels use 12px.
- **JetBrains Mono** is mandatory for all technical data: rule IDs, file paths, hashes, and code snippets. The monospaced nature ensures that columns of data remain aligned and easily scannable.
- **Scaling:** Unlike consumer apps, there is minimal size difference between mobile and desktop as the tool is intended for desktop-first workflows. We use "base" and "small" variants to handle information hierarchy within compact layouts.
- **Emphasis:** Use semi-bold (600) for section headers and uppercase tracking for small labels to differentiate them from interactive text.

## Layout & Spacing
This system utilizes a strict **4px grid** to achieve IDE-level density. 

- **Layout Model:** A fluid grid with fixed sidebars. Sidebars and navigation panels are collapsible to maximize the central workspace.
- **Density:** Padding within components (like table cells or list items) is kept to a minimum (typically 8px horizontal, 4px vertical).
- **Breakpoints:**
    - **Desktop (1280px+):** Full 12-column layout with multi-pane view (Explorer, Editor, Inspector).
    - **Tablet (768px-1279px):** Inspector panel becomes a slide-over or bottom sheet.
    - **Mobile (Under 768px):** Essential status views only; complex tables reflow into card-style lists with horizontal scrolling for code blocks.

## Elevation & Depth
Depth is created through **Tonal Layers** and **Subtle Outlines** rather than traditional shadows.

- **Stacking:** The background is the lowest layer. Panels and sidebars sit on top, differentiated by a 1px border (#334155 in dark mode) or a slightly lighter/darker background color.
- **Borders:** Every logical section must be separated by a 1px solid border. This mimics the structured feel of a development tool.
- **Popovers:** Only modals and context menus use a shadow. Use a sharp, low-spread shadow with 40% opacity to ensure the element "pops" without feeling soft or blurry.

## Shapes
The shape language is "Soft-Industrial." 

The system uses a consistent **4px (0.25rem)** corner radius for almost all elements including buttons, input fields, and cards. This provides a clean, modern look while maintaining the rigid, structured feel necessary for a technical tool. Large rounded corners (pills) are strictly reserved for status badges to make them instantly recognizable as distinct from interactive buttons.

## Components
- **Buttons:** Compact height (28px or 32px). Primary buttons use the indigo background; secondary buttons use a ghost style with a subtle border.
- **Data Tables:** The core of the tool. Rows are 32px high with 1px borders. Use alternating row stripes (Zebra striping) in dark mode for better tracking across wide screens.
- **Status Badges:** Compact pill shapes containing a 12px icon and 11px uppercase text. Backgrounds use 15% opacity of the semantic color with a solid 1px border.
- **Code Snippets:** Always rendered in JetBrains Mono with a subtle background block. Syntax highlighting must be accessible and follow the semantic color rules (e.g., errors in red).
- **Tabs:** "Editor-style" tabs. Squared off with a 2px colored bottom border for the active state. No background fill for inactive tabs to reduce visual noise.
- **Input Fields:** Inset appearance with 1px borders. Focused state uses a 1px primary color glow without increasing the element's footprint.