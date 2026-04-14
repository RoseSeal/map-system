package com.whut.map.map_service.tracking.store;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DerivedTargetStateStore {
    private final ConcurrentHashMap<String, TargetDerivedSnapshot> snapshots = new ConcurrentHashMap<>();

    public void put(String targetId, TargetDerivedSnapshot snapshot) {
        snapshots.put(targetId, snapshot);
    }

    public TargetDerivedSnapshot get(String targetId) {
        return snapshots.get(targetId);
    }

    public void remove(String targetId) {
        snapshots.remove(targetId);
    }

    public Map<String, TargetDerivedSnapshot> getAll() {
        return Collections.unmodifiableMap(snapshots);
    }
    
    public void clear() {
        snapshots.clear();
    }
}
