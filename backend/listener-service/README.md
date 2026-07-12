# listener-service (dormant)

Offline-analysis ingestion service: persists external MQTT input for later
batch analysis, decoupled from the real-time risk chain in `map-service`.

**Status: dormant.** This service is not part of the current runtime topology
(`compose.yaml` does not start it) and is not required by any live feature.
Reviving the offline-analysis link is tracked in `docs/TODO.md` §1.
