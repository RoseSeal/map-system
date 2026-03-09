# Unmanned Fleet UI

WebGL-based situational awareness frontend for an unmanned fleet risk warning system.

## Project Overview

Core goals:
- Render a 2.5D maritime chart (S-57 ENC) in real time.
- Visualize dynamic vessel risk information (CPA, OZT, Safety Domain, predicted trajectory).
- Support both mock-data mode and backend-connected mode.

Current operating mode:
- Active mode: mock-data mode.
- Backend mode: planned, currently under reconstruction.
- Development focus: frontend rendering and interaction behavior.
- Data source during local runs: frontend mock generators and local state updates.

Current project status:
- The frontend currently runs in mock-data mode for day-to-day development.
- The backend is under reconstruction and is documented as a future provider contract.

README scope:
- This file is quick start only.
- It intentionally excludes backend startup instructions.
- It keeps install and run steps minimal for local frontend development.

## Tech Stack Summary

Frontend:
- React 18+ (functional components + hooks)
- Vite 5+
- TypeScript (strict typing)
- Zustand (state management)
- MapLibre GL JS 4+
- Deck.gl 9+ (MapboxOverlay integration)
- Tailwind CSS 3+

Rendering and visualization roles:
- React drives UI composition and overlay controls.
- MapLibre renders the base map context.
- Deck.gl renders dynamic risk and vessel overlays.
- Zustand coordinates shared frontend state.

Browser baseline:
- Chrome 90+
- Firefox 90+
- Edge 90+
- WebGL 2.0 support required

Recommended local environment:
- Node.js 18+ or newer
- npm 9+ or newer
- Modern GPU driver support for stable WebGL rendering

Tooling expectations:
- Node.js 18+
- npm 9+

## Quick Start

Project path:
- `map-system/frontend`

1. Open a terminal at `map-system/frontend`.
2. Install dependencies.
3. Start the Vite development server.
4. Open the local URL shown by Vite.
5. Validate that the app loads with mock-driven dynamic updates.

Install dependencies:

```bash
npm install
```

Start the development server:

```bash
npm run dev
```

Expected local behavior after startup:
- The app launches in frontend mock-data mode.
- Dynamic vessel/risk visuals should update without backend services.
- If backend-specific layers are unavailable, the app remains usable for frontend work.

## Documentation Split

Detailed references moved to:
- `backend-api.md` for endpoint definitions and backend contract details.
- `../docs/frontend-design.md` for architecture, rendering rules, and performance notes.