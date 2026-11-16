package com.ainpc.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class NpcDecisionResponse {
    private String npcId;
    private String dialogue;
    private String thought;
    private String intentAction;
    private String nextTurnIntent;
    @JsonProperty("isBluff")
    private boolean bluff;
    @JsonProperty("isNonsense")
    private boolean nonsense;
    private Map<String, Integer> globalStatDeltas = new HashMap<>();
    private Map<String, Integer> npcStatDeltas = new HashMap<>();
    private RumorPayload rumorToShare;
    private List<QuickReply> suggestedReplies = new ArrayList<>();

    @Data
    public static class RumorPayload {
        private String targetNpcId;
        private String content;
    }

    @Data
    public static class QuickReply {
        private String label;
        private String text;
    }
}
