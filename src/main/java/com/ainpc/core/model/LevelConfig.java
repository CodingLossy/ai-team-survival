package com.ainpc.core.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class LevelConfig {
    private String levelId;
    private GameType gameType;
    private String title;
    private String description;
    private int maxTurns = 8;
    private boolean hellMode = false;
    private String secretTruth;
    private List<String> playerCards = new ArrayList<>();
    private List<String> npcKnownFacts = new ArrayList<>();
    private Map<String, Integer> globalStats = new HashMap<>();
    private String winCondition;
    private String loseCondition;
    private List<NpcProfile> npcs = new ArrayList<>();
    private List<RuleHook> ruleHooks = new ArrayList<>();

    public enum GameType {
        INTERROGATION,
        TEAM_SURVIVAL
    }

    @Data
    public static class NpcProfile {
        private String npcId;
        private String name;
        private String role;
        private String avatarTag;
        private String personaPrompt;
        private Map<String, Integer> initialStats = new HashMap<>();
        private List<String> allowedActions = new ArrayList<>();
    }

    @Data
    public static class RuleHook {
        private String statKey;
        private String operator;
        private int threshold;
        private String forcedAction;
        private String description;
    }
}
