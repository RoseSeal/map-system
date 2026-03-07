# Frontend Design Reference

This document consolidates the frontend design rationale, architecture, rendering behavior,
and operational guidance for the unmanned fleet UI.

## 1. Project Background

This project is a WebGL-based situational awareness frontend for an unmanned fleet risk warning system.

Core goals:
- Render a 2.5D maritime chart (S-57 ENC) in real time.
- Visualize dynamic vessel risk information (CPA, OZT, Safety Domain, predicted trajectory).
- Support both mock data mode and backend-connected mode.

Primary frontend app path:
- `unmanned-fleet-ui`

## 2. Architecture Model

The system follows a Fat Backend + Thin Frontend model:
- Backend handles S-57 parsing/adaptation, vector tile service, risk calculation, and streaming updates.
- Frontend focuses on rendering and interaction.

Logical layers:
1. Data Foundation: AIS + S-57 data processing.
2. Static Risk: S-57 to MVT service (PostGIS ST_AsMVT).
3. Dynamic Risk: Risk object generation (real stream or mock backend).
4. Visualization: React + MapLibre + Deck.gl rendering.

## 3. Frontend Technology Stack

- React 18+ (functional components + hooks)
- Vite 5+
- TypeScript (strict typing)
- Zustand (state management)
- MapLibre GL JS 4+
- Deck.gl 9+ (MapboxOverlay integration)
- Tailwind CSS 3+

Browser baseline:
- Chrome 90+
- Firefox 90+
- Edge 90+
- WebGL 2.0 support required

## 4. Repository Structure

```text
unmanned-fleet-ui/
  src/
    components/
      Dashboard/
      Map/
      Overlays/
    config/
      constants.ts
      layerStyles.ts
    services/
      socketService.ts
      s57Service.ts
      mockDataGenerator.ts
    store/
      useRiskStore.ts
    types/
      schema.d.ts
    utils/
      geoUtils.ts
```

## 5. Data Layers and Contract Model

Primary render unit:
- `RiskObject`

Important fields:
- Metadata and governance
- `risk_object_id`, `timestamp`, `governance.mode`, `governance.trust_factor`
- Own ship
- `position`, `dynamics` (`sog`, `cog`, `hdg`, `rot`), `platform_health`
- Prediction and domain
- `future_trajectory` (`linear` / `curved_headline`), `safety_domain`
- Targets and risk
- `risk_level`, `cpa_metrics`, `graphic_cpa_line`, `ozt_sector`
- Environment
- `environment_context.safety_contour_val`

Risk levels:
- `SAFE`, `CAUTION`, `WARNING`, `ALARM`

Platform health states:
- `NORMAL`, `DEGRADED`, `NUC`

## 6. Rendering Rules

Map and overlay layering order:
1. MapLibre base map (bottom)
2. Deck.gl dynamic overlays (middle)
3. React UI overlays (top)

S-57 map style guidance:
- Land (`LNDARE`): base fill plus optional 3D extrusion
- Depth areas (`DEPARE`): shallow/deep color split by depth value
- Restricted zones (`RESARE`): transparent red warning fill
- Additional chart layers: `COALNE`, `DEPCNT`, `SOUNDG`

Behavioral display thresholds:
- Show safety domain when risk level >= `CAUTION`
- Show CPA line when risk level == `ALARM`
- Show low-trust warning when `trust_factor < 0.4`

## 7. Coordinate and Validation Region

Current chart coverage and validation area:
- Jamaica Bay, New York
- Center: `[-73.7625, 40.6125]`

Default view and mock vessel positions should align with this region.
If they do not, chart data may appear missing.

## 8. Local Frontend Development

Frontend startup:

```bash
cd d:\Project\FrontEnd\unmanned-fleet-ui
npm install
npm run dev
```

Integration verification in browser dev tools:
- No CORS errors
- Tile requests return HTTP 200
- Chart features visible in Jamaica Bay area

Example tile request used during validation:
- `http://localhost:8081/api/s57/tiles/12/1203/1535.pbf`

## 9. Troubleshooting Quick Guide

Issue: Blank map or no chart features
- Confirm backend health endpoint is reachable.
- Confirm tile requests are 200 in Network panel.
- Confirm map center is in Jamaica Bay coverage.
- Test zoom range around 10-15.

Issue: CORS errors
- Restart backend and browser.
- Recheck backend CORS configuration.

Issue: Features not visible at current zoom
- Some layers are zoom-dependent.
- Increase or decrease zoom and re-check.

Issue: Frontend appears to jump to wrong region
- Validate `DEFAULT_VIEW_STATE` and mock base coordinates in:
- `unmanned-fleet-ui/src/config/constants.ts`
- `unmanned-fleet-ui/src/services/mockDataGenerator.ts`

## 10. Performance Notes

Performance targets:
- Target frame rate: 60 FPS
- Graceful fallback when target count is high
- Typical update cadence: 100 ms interpolation window
- Practical target count for MVP: up to around 1000 moving objects

## 11. Acceptance Checklist

- Backend starts without runtime errors.
- Frontend compiles and launches successfully.
- S-57 tiles load from backend endpoints.
- Land/depth/coastline layers render correctly.
- Dynamic vessel overlays render and update.
- Risk overlays (domain/OZT/CPA) follow threshold rules.
- No critical console errors in browser.

## 12. Notes

- This split documentation replaces the previous single README as source of truth.
- Backup files (`*.backup`) remain available for code-level rollback when needed.
