package com.ainpc.core.service;

import com.ainpc.core.engine.RuleValidationEngine;
import com.ainpc.core.llm.LlmClient;
import com.ainpc.core.model.GameSessionState;
import com.ainpc.core.model.LevelConfig;
import com.ainpc.core.model.NpcDecisionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class GameSessionService {

    private final LevelCompilerService levelCompilerService;
    private final LlmClient llmClient;
    private final RuleValidationEngine ruleValidationEngine;
    private final Map<String, GameSessionState> sessions = new ConcurrentHashMap<>();

    public GameSessionState startSession(String levelId) {
        LevelConfig cfg = levelCompilerService.getLevel(levelId);
        if (cfg == null) {
            throw new IllegalArgumentException("Level not found: " + levelId);
        }
        GameSessionState state = new GameSessionState();
        state.setSessionId(UUID.randomUUID().toString());
        state.setLevelConfig(cfg);
        state.getGlobalStats().putAll(cfg.getGlobalStats());

        for (LevelConfig.NpcProfile npc : cfg.getNpcs()) {
            GameSessionState.NpcRuntimeState rt = new GameSessionState.NpcRuntimeState();
            rt.setNpcId(npc.getNpcId());
            rt.getStats().putAll(npc.getInitialStats());
            state.getNpcStates().put(npc.getNpcId(), rt);
        }
        sessions.put(state.getSessionId(), state);
        return state;
    }

    public Map<String, Object> interact(
            String sessionId,
            String targetNpcId,
            String playerMessage,
            RuleValidationEngine.SensorMetrics sensors) {

        GameSessionState session = sessions.get(sessionId);
        if (session == null) throw new IllegalArgumentException("Session not found: " + sessionId);
        if (session.getStatus() != GameSessionState.SessionStatus.RUNNING) {
            return Map.of("session", session, "message", "对局已结束：" + session.getEndReason());
        }

        LevelConfig cfg = session.getLevelConfig();
        LevelConfig.NpcProfile profile = cfg.getNpcs().stream()
                .filter(n -> n.getNpcId().equals(targetNpcId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("NPC not found: " + targetNpcId));

        GameSessionState.NpcRuntimeState npcState = session.getNpcStates().get(targetNpcId);
        List<String> rumors = new ArrayList<>(npcState.getPendingRumors());
        npcState.getPendingRumors().clear();

        boolean isInterrogation = cfg.getGameType() == LevelConfig.GameType.INTERROGATION;
        String extraGuide;
        if (isInterrogation) {
            extraGuide = """
                    你掌握的客观死证据：%s。
                    玩家对外伪造的底牌设定：%s。
                    数值平衡要求：请拿玩家的回答硬碰客观证据以及前几轮说过的话；若解释合理且自圆其说，请给予正向反馈（suspicion 给 -10 到 -20，trust 给 +10）；仅当明显矛盾时增加 suspicion（+10 到 +20）。你可以偶尔故意抛出一句假证据来诈唬玩家（此时必须将 intentAction 设为 BLUFF_EVIDENCE 且 isBluff 设为 true）。
                    """.formatted(cfg.getNpcKnownFacts(), cfg.getPlayerCards());
        } else {
            String hellHint = cfg.isHellMode()
                    ? "地狱模式规则：常规推进最多到 99%。只有当玩家在对话中精准击中验收痛点并给出完整兜底方案时，才允许触发 FINAL_SIGN_OFF 完成终极验收！"
                    : "";
            extraGuide = "数值平衡要求：若玩家安抚得当或给出合理方案，请积极推进进度（progress 给 +15 到 +28），不要每轮大幅加压。" + hellHint;
        }

        String replyStyles = isInterrogation ? "强硬反问、模糊拖延、顺水推舟" : "画饼安抚、务实推进、甩锅转移";

        String systemPrompt = """
                你正在扮演游戏中的 AI NPC：%s（%s）。
                人设：%s
                当前你的状态：%s
                当前全局状态：%s
                你刚收到的队友传话：%s
                你的近期记忆：%s
                允许的动作白名单：%s
                %s
                同时请结合你刚才的回复与当前上下文，为玩家生成 3 条不同态度（%s）的口语化快捷回复建议（suggestedReplies），每条都要紧扣当前具体对话细节，绝不套话重复。
                请严格输出 JSON：
                {
                  "npcId": "%s",
                  "dialogue": "你的回复台词",
                  "thought": "内心想法",
                  "intentAction": "必须从允许的动作白名单中选一个",
                  "isBluff": false,
                  "globalStatDeltas": {},
                  "npcStatDeltas": {},
                  "rumorToShare": null,
                  "suggestedReplies": [
                    {"label": "态度标签", "text": "紧扣当前上下文的具体口语回复"}
                  ]
                }
                """.formatted(
                profile.getName(), profile.getRole(), profile.getPersonaPrompt(),
                npcState.getStats(), session.getGlobalStats(), rumors,
                npcState.getPrivateMemories(), profile.getAllowedActions(),
                extraGuide, replyStyles, profile.getNpcId()
        );

        NpcDecisionResponse decision = llmClient.chatJson(systemPrompt, playerMessage, NpcDecisionResponse.class);
        npcState.getPrivateMemories().add("玩家：" + playerMessage + " | 我：" + decision.getDialogue());

        RuleValidationEngine.ValidationResult validation = ruleValidationEngine
                .validateAndApply(session, profile, decision, sensors);

        session.setCurrentTurn(session.getCurrentTurn() + 1);

        return Map.of("session", session, "decision", decision, "validation", validation);
    }

    public Map<String, Object> performAction(String sessionId, String actionType, String targetNpcId, Map<String, Integer> customDeltas) {
        GameSessionState session = sessions.get(sessionId);
        if (session == null) throw new IllegalArgumentException("Session not found: " + sessionId);
        String logMsg = "执行战术动作：" + actionType;

        if ("RED_PACKET".equals(actionType)) {
            int budget = session.getGlobalStats().getOrDefault("budget", 0);
            if (budget >= 1000 && targetNpcId != null) {
                session.getGlobalStats().put("budget", budget - 1000);
                var st = session.getNpcStates().get(targetNpcId);
                if (st != null && st.isActive()) {
                    st.getStats().put("pressure", Math.max(0, st.getStats().getOrDefault("pressure", 0) - 25));
                    st.getStats().put("trust", Math.min(100, st.getStats().getOrDefault("trust", 50) + 15));
                }
                logMsg = "💸 经理自掏腰包发了 1000 元红包，目标同事压力 -25、信任 +15！";
            } else {
                logMsg = "💸 经费不足 1000，红包发送失败！";
            }
        } else if ("TEAM_DINNER".equals(actionType)) {
            int budget = session.getGlobalStats().getOrDefault("budget", 0);
            if (budget >= 2500) {
                session.getGlobalStats().put("budget", budget - 2500);
                session.getNpcStates().values().forEach(st -> {
                    if (st.isActive()) {
                        st.getStats().put("pressure", Math.max(0, st.getStats().getOrDefault("pressure", 0) - 20));
                        st.getPendingRumors().clear();
                    }
                });
                logMsg = "🍕 组织全员聚餐，清空了所有私下传话谣言，全员压力 -20！";
            } else {
                logMsg = "🍕 经费不足 2500，聚餐取消！";
            }
        } else if ("DRINK_WATER".equals(actionType)) {
            int sus = session.getGlobalStats().getOrDefault("suspicion", 0);
            session.getGlobalStats().put("suspicion", Math.max(0, sus - 8));
            logMsg = "🥤 你镇定地喝了口水稳住心率，怀疑度 -8。";
        } else if ("ALIBI_CARD".equals(actionType)) {
            int sus = session.getGlobalStats().getOrDefault("suspicion", 0);
            session.getGlobalStats().put("suspicion", Math.max(0, sus - 20));
            logMsg = "📄 出示提前准备的佐证，怀疑度大幅下降 -20！";
        } else if ("BLAME_WITNESS".equals(actionType)) {
            int sus = session.getGlobalStats().getOrDefault("suspicion", 0);
            session.getGlobalStats().put("suspicion", Math.max(0, sus - 15));
            logMsg = "🎭 你祸水东引分散了审讯官注意力，怀疑度 -15！";
        } else if ("CALL_OUT_BLUFF".equals(actionType)) {
            int wasBluff = session.getGlobalStats().getOrDefault("lastWasBluff", 0);
            int sus = session.getGlobalStats().getOrDefault("suspicion", 0);
            if (wasBluff == 1) {
                session.getGlobalStats().put("suspicion", Math.max(0, sus - 30));
                session.getGlobalStats().put("lastWasBluff", 0);
                logMsg = "💥 拍桌反杀成功！你当场拆穿了审讯官的假证据，怀疑度暴降 -30%！";
            } else {
                session.getGlobalStats().put("suspicion", Math.min(100, sus + 20));
                logMsg = "😬 拍桌翻车！对方没有诈唬，心虚反咬让怀疑度飙升 +20%！";
            }
            ruleValidationEngine.evaluateEndState(session);
        } else if ("CRISIS_CHOICE".equals(actionType) && customDeltas != null) {
            int progressCap = session.getLevelConfig().isHellMode() ? 99 : 100;
            customDeltas.forEach((k, v) -> {
                if (session.getGlobalStats().containsKey(k)) {
                    int cap = "progress".equals(k) ? progressCap : 100000;
                    int next = Math.max(0, Math.min(cap, session.getGlobalStats().get(k) + v));
                    session.getGlobalStats().put(k, next);
                }
            });
            logMsg = "⚡ 突发危机已处理，全局状态已更新。";
        }

        session.getGlobalEventLog().add(logMsg);
        return Map.of("session", session, "log", logMsg);
    }
}
