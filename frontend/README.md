# Map System Frontend

WebGL-based situational awareness frontend for the map-system collision risk assessment service.

## Project Overview

Core goals:
- Render a 2.5D maritime map in real time: Esri satellite imagery as the base map, with S-57 ENC layers where chart data exists.
- Visualize dynamic vessel risk information (CPA, OZT, Safety Domain, predicted trajectory).
- Consume live vessel, risk, explanation and advisory data from the backend (`/api/v2/risk` SSE and `/api/v2/chat` WebSocket).

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
5. Start the backend and a simulator (see `../docs/demo/MAP_SYSTEM_DEMO_SCRIPT.md`) to see live vessels and risk data.

Install dependencies:

```bash
npm install
```

Start the development server:

```bash
npm run dev
```

Expected local behavior after startup:
- There is no built-in mock-data mode; vessels and risk visuals appear only when the backend is publishing data.
- The base map needs internet access for Esri imagery tiles.

## Documentation Split

Detailed references moved to:
- `../docs/EVENT_SCHEMA.md` for current SSE / WebSocket contract details.
- `../docs/frontend-design.md` for frontend architecture, rendering rules, and interaction boundaries.
- `../CURRENT_SYSTEM_OVERVIEW.md` for cross-module repository orientation.
