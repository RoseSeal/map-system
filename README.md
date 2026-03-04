# Map System

Real-time maritime AIS visualization and warning system.

## Overview

This project implements a backend system for real-time maritime monitoring using AIS data.

Main capabilities:

- Real-time AIS data streaming
- 2.5D maritime map visualization
- Collision warning based on CPA/TCPA rules
- Planned route visualization

## Architecture

MQTT → Backend (Spring Boot) → WebSocket → Frontend

## Documentation

- System Design: docs/v0.5-design.md
- Development Plan: docs/plan.md

## Tech Stack

Backend:
- Java
- Spring Boot
- MQTT
- WebSocket

Database:
- PostgreSQL
- PostGIS

Frontend:
- 2.5D maritime map visualization