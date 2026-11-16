package com.ainpc.core.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
public class GameSessionState {
    private String sessionId;
    private LevelConfig levelConfig;
    private int currentTurn = 1;
    private SessionStatus status = SessionStatus.RUNNING;
    private String endReason;
    private Map<String, Integer> globalStats = new ConcurrentHashMap<>();
    private Map<String, NpcRuntimeState> npcStates = new ConcurrentHashMap<>();
    private List<String> globalEventLog = new ArrayList<>();

    public enum SessionStatus {
        RUNNING, WON, LOST
    }

    @Data
    public static class NpcRuntimeState {
        private String npcId;
        private boolean active = true;
        private Map<String, Integer> stats = new HashMap<>();
        private List<String> privateMemories = new ArrayList<>();
        private List<String> pendingRumors = new ArrayList<>();
    }
}
