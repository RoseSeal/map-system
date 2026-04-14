with open("src/main/java/com/whut/map/map_service/pipeline/ShipDispatcher.java", "r") as f:
    content = f.read()

old_dispatchFull = """    private ShipDerivedOutputs dispatchFull(ShipDispatchContext context) {
        ShipDerivedOutputs outputs = runDerivations(context);
        this.cachedOwnShipDomainResult = outputs.shipDomainResult();
        
        for (ShipStatus target : context.allShips()) {
            if (target.getId().equals(context.ownShip().getId())) continue;"""
new_dispatchFull = """    public void refreshAfterCleanup() {
        ShipDispatchContext context = new ShipDispatchContext(
                null, shipStateStore.getOwnShip(), shipStateStore.getAll());
        if (!context.hasOwnShip()) return;
        ShipDerivedOutputs outputs = dispatchFull(context);
        RiskDispatchSnapshot snapshot = buildRiskSnapshot(context, outputs, false);
        if (snapshot != null) {
            publishRiskSnapshot(snapshot);
        }
    }

    private ShipDerivedOutputs dispatchFull(ShipDispatchContext context) {
        ShipDerivedOutputs outputs = runDerivations(context);
        this.cachedOwnShipDomainResult = outputs.shipDomainResult();
        
        if (!context.hasOwnShip()) {
            return outputs;
        }

        for (ShipStatus target : context.allShips()) {
            if (target.getId().equals(context.ownShip().getId())) continue;"""
content = content.replace(old_dispatchFull, new_dispatchFull)

with open("src/main/java/com/whut/map/map_service/pipeline/ShipDispatcher.java", "w") as f:
    f.write(content)
