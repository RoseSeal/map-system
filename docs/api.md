# Backend API Contract
Status: Draft  
Consumer: unmanned-fleet-ui frontend  
Provider: future Spring Boot backend

## Scope

This document captures the backend interface and data contract consumed by the frontend.
It includes API endpoints, expected semantics, and key structures used by the map and risk overlays.

## Backend Responsibility

The backend follows the original Fat Backend + Thin Frontend split:
- S-57 parsing and adaptation
- Vector tile generation and serving
- Risk calculation
- Streaming updates for dynamic objects

## Base Integration Context

S-57 backend base path:
- `http://localhost:8081/api/s57`

Primary frontend consumer path:
- `src/services/s57Service.ts`

Frontend configuration references:
- `src/config/constants.ts`
- `src/config/layerStyles.ts`

## Endpoint List

### 1. Vector Tiles
- URL: `GET /tiles/{z}/{x}/{y}.pbf`
- Full example: `http://localhost:8081/api/s57/tiles/12/1203/1535.pbf`
- Purpose: returns Mapbox Vector Tile data used for chart feature rendering.

### 2. Style Definition
- URL: `GET /style.json`
- Full example: `http://localhost:8081/api/s57/style.json`
- Purpose: returns style payload for S-57 layer visualization.

### 3. Layer Metadata
- URL: `GET /layers`
- Full example: `http://localhost:8081/api/s57/layers`
- Purpose: returns available chart layer metadata used by frontend layer management.

### 4. Safety Contour
- URL: `GET /safety-contour`
- Full example: `http://localhost:8081/api/s57/safety-contour`
- Purpose: provides safety contour value/context used in risk/environment rendering.

### 5. Health Check
- URL: `GET /health`
- Full example: `http://localhost:8081/api/s57/health`
- Purpose: service availability probe for integration checks.
- Expected state: response indicates `UP`.

## Interface Contract: RiskObject

Primary frontend render unit:
- `RiskObject`

Important fields:
- Metadata and governance:
- `risk_object_id`, `timestamp`, `governance.mode`, `governance.trust_factor`
- Own ship:
- `position`, `dynamics` (`sog`, `cog`, `hdg`, `rot`), `platform_health`
- Prediction and domain:
- `future_trajectory` (`linear` / `curved_headline`), `safety_domain`
- Targets and risk:
- `risk_level`, `cpa_metrics`, `graphic_cpa_line`, `ozt_sector`
- Environment:
- `environment_context.safety_contour_val`

Risk level enum contract:
- `SAFE`
- `CAUTION`
- `WARNING`
- `ALARM`

Platform health enum contract:
- `NORMAL`
- `DEGRADED`
- `NUC`

## Contract-Coupled Rendering Thresholds

The frontend applies these rule thresholds against backend payload fields:
- Show safety domain when `risk_level >= CAUTION`
- Show CPA line when `risk_level == ALARM`
- Show low-trust warning when `governance.trust_factor < 0.4`

## Data Region Contract (Current Validation Coverage)

Current chart coverage used in integration tests:
- Jamaica Bay, New York
- Center: `[-73.7625, 40.6125]`

The frontend default view and mock base coordinates should align with this area.
If coordinates drift outside this region, chart data may appear missing even when API calls succeed.

## Local Integration Verification

### Backend Startup (legacy reference)

```bash
cd d:\Project\java\ENC-Safety-Service\geointel-dashboard
mvn spring-boot:run
```

### Verification Checklist

In browser developer tools:
- No CORS errors
- Tile requests return HTTP 200
- Chart features are visible in Jamaica Bay coverage

## Troubleshooting (API and Contract)

Issue: Blank map or no chart features
- Confirm the health endpoint is reachable.
- Confirm tile requests return HTTP 200.
- Confirm map center remains in Jamaica Bay coverage.
- Re-test around zoom range 10-15.

Issue: CORS errors
- Restart backend and browser.
- Recheck backend CORS configuration.

Issue: Features not visible at current zoom
- Some chart layers are zoom-dependent.
- Adjust zoom in and out to verify visibility.

Issue: Frontend jumps to wrong region
- Validate `DEFAULT_VIEW_STATE` in `src/config/constants.ts`.
- Validate mock base coordinates in `src/services/mockDataGenerator.ts`.
