package com.ainpc.core.service;

import com.ainpc.core.engine.RuleValidationEngine;
import com.ainpc.core.llm.LlmClient;
import com.ainpc.core.model.GameSessionState;
import com.ainpc.core.model.LevelConfig;
import com.ainpc.core.model.NpcDecisionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class GameSessionService {

    private final LevelCompilerService levelCompilerService;
    private final LlmClient llmClient;
    private final RuleValidationEngine ruleValidationEngine;
    private final Map<String, GameSessionState> sessions = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public GameSessionState startSession(String levelId) {
        LevelConfig cfg = levelCompilerService.getLevel(levelId);
        if (cfg == null) throw new IllegalArgumentException("Level not found: " + levelId);
        GameSessionState state = new GameSessionState();
        state.setSessionId(UUID.randomUUID().toString());
        state.setLevelConfig(cfg);
        state.getGlobalStats().putAll(cfg.getGlobalStats());
        if (cfg.getGameType() == LevelConfig.GameType.INTERROGATION) {
            state.getGlobalStats().putIfAbsent("bossRage", 0);
            state.getGlobalStats().putIfAbsent("bsStreak", 0);
        }

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
                    判断规则：
                    1. 如果玩家在故意装傻、胡说八道或完全答非所问，请把 isNonsense 设为 true（同时根据荒诞程度给 suspicion 加分）；如果玩家解释合情合理、能自圆其说，isNonsense 设为 false 且给予正向反馈（suspicion 给 -10 到 -20，trust 给 +10）。
                    2. 你可以偶尔故意抛出一句假证据来诈唬玩家（此时 intentAction 设为 BLUFF_EVIDENCE 且 isBluff 设为 true）。
                    """.formatted(cfg.getNpcKnownFacts(), cfg.getPlayerCards());
        } else {
            String hellHint = cfg.isHellMode()
                    ? "地狱模式规则：常规推进最多到 99%。只有当玩家在对话或出牌中精准击中验收痛点、给出兜底方案时，才触发 FINAL_SIGN_OFF 完成终极验收！"
                    : "";
            extraGuide = "数值平衡要求：若玩家安抚或出牌得当，请积极推进进度（progress 给 +12 到 +25），并给出你下一回合的行动意图 nextTurnIntent（例如：准备摸鱼 / 准备开喷 / 准备赶工）。" + hellHint;
        }

        String replyStyles = isInterrogation ? "强硬反问、离谱鬼扯、顺水推舟" : "画饼安抚、务实推进、甩锅转移";

        String systemPrompt = """
                你正在扮演游戏中的 AI NPC：%s（%s）。
                人设：%s
                当前你的状态：%s
                当前全局状态：%s
                你刚收到的队友传话：%s
                你的近期记忆：%s
                允许的动作白名单：%s
                %s
                同时请结合你刚才的回复与当前上下文，为玩家生成 3 条不同态度（%s）的口语化快捷回复建议（suggestedReplies），紧扣当前细节。
                请严格输出 JSON：
                {
                  "npcId": "%s",
                  "dialogue": "你的回复台词",
                  "thought": "内心想法",
                  "intentAction": "必须从允许的动作白名单中选一个",
                  "nextTurnIntent": "下回合意图简述",
                  "isBluff": false,
                  "isNonsense": false,
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

    public Map<String, Object> playCard(String sessionId, String cardId, String targetNpcId, String note) {
        GameSessionState session = sessions.get(sessionId);
        if (session == null) throw new IllegalArgumentException("Session not found: " + sessionId);
        if (session.getStatus() != GameSessionState.SessionStatus.RUNNING) {
            return Map.of("session", session, "comboTriggered", false, "cardLog", "对局已结束");
        }

        LevelConfig cfg = session.getLevelConfig();
        int progressCap = cfg.isHellMode() ? 99 : 100;
        int curProg = session.getGlobalStats().getOrDefault("progress", 0);
        int curBudget = session.getGlobalStats().getOrDefault("budget", 0);
        var targetState = session.getNpcStates().get(targetNpcId);

        String lastCard = session.getGlobalEventLog().stream()
                .filter(s -> s.startsWith("CARD_KEY:"))
                .reduce((a, b) -> b)
                .map(s -> s.replace("CARD_KEY:", ""))
                .orElse("");

        String comboMsg = null;
        int bonusProgress = 0;
        int bonusTrust = 0;

        // Check unexpected Card Combos!
        if ("CARD_PIE".equals(lastCard) && "CARD_RED_PACKET".equals(cardId)) {
            comboMsg = "✨ 触发连招【真香定律】！（画大饼 + 发红包）额外进度 +12%，目标信任 +20！";
            bonusProgress = 12;
            bonusTrust = 20;
        } else if ("CARD_BOSS_INSPECT".equals(lastCard) && "CARD_ICED_COFFEE".equals(cardId)) {
            comboMsg = "✨ 触发连招【高压续命】！（老板视察 + 全员冰美式）额外进度 +15%，抵消信任惩罚！";
            bonusProgress = 15;
            bonusTrust = 15;
        } else if ("CARD_UNPLUG_NET".equals(lastCard) && "CARD_FORTUNE".equals(cardId)) {
            comboMsg = "✨ 触发连招【玄学回滚】！（物理拔网线 + 算一卦）服务器起死回生，额外进度 +18%！";
            bonusProgress = 18;
        }

        int progDelta = 0;
        int budgetDelta = 0;
        int pressDelta = 0;
        int trustDelta = 0;
        String cardEffectDesc;

        switch (cardId) {
            case "CARD_PIE" -> {
                progDelta = 10; pressDelta = 10; trustDelta = -5;
                cardEffectDesc = "🥧 打出【画大饼】：进度 +10%，对方压力 +10";
            }
            case "CARD_RED_PACKET" -> {
                if (curBudget < 1000) {
                    return Map.of("session", session, "comboTriggered", false, "cardLog", "💸 经费不足 1000，无法打出【发红包】！");
                }
                budgetDelta = -1000; pressDelta = -25; trustDelta = 15; progDelta = 6;
                cardEffectDesc = "💸 打出【发红包】：经费 -1000，压力 -25，进度 +6%";
            }
            case "CARD_FORTUNE" -> {
                boolean lucky = random.nextBoolean();
                progDelta = lucky ? 24 : 4;
                pressDelta = lucky ? -10 : 18;
                cardEffectDesc = lucky ? "🔮 打出【算一卦再上线】：大吉！进度暴涨 +24%，压力 -10！" : "🔮 打出【算一卦再上线】：忌上线！进度仅 +4%，全员压力 +18！";
            }
            case "CARD_UNPLUG_NET" -> {
                if (curBudget < 500) {
                    return Map.of("session", session, "comboTriggered", false, "cardLog", "💸 经费不足 500，拔不起网线！");
                }
                budgetDelta = -500;
                session.getNpcStates().values().forEach(s -> s.getPendingRumors().clear());
                pressDelta = -15;
                cardEffectDesc = "🧹 打出【物理拔网线】：强行断网消除了所有八卦传话，压力 -15！";
            }
            case "CARD_BOSS_INSPECT" -> {
                progDelta = 18; trustDelta = -15; pressDelta = 20;
                cardEffectDesc = "🕵️ 打出【假装老板视察】：全员不敢摸鱼，进度 +18%，但信任 -15！";
            }
            case "CARD_ICED_COFFEE" -> {
                if (curBudget < 400) {
                    return Map.of("session", session, "comboTriggered", false, "cardLog", "💸 经费不足 400，买不起冰美式！");
                }
                budgetDelta = -400; pressDelta = -15; progDelta = 8;
                cardEffectDesc = "🧋 打出【全员冰美式】：提神续命！进度 +8%，压力 -15！";
            }
            case "CARD_DROP_DB" -> {
                progDelta = 28; pressDelta = 35; trustDelta = -20;
                cardEffectDesc = "🔥 打出【高危热更新】：进度狂飙 +28%，但目标压力暴涨 +35！";
            }
            default -> {
                progDelta = 8;
                cardEffectDesc = "🃏 打出常规协调卡：进度 +8%";
            }
        }

        session.getGlobalStats().put("progress", Math.min(progressCap, Math.max(0, curProg + progDelta + bonusProgress)));
        session.getGlobalStats().put("budget", Math.max(0, curBudget + budgetDelta));
        if (targetState != null && targetState.isActive()) {
            int np = Math.max(0, Math.min(100, targetState.getStats().getOrDefault("pressure", 0) + pressDelta));
            int nt = Math.max(0, Math.min(100, targetState.getStats().getOrDefault("trust", 50) + trustDelta + bonusTrust));
            targetState.getStats().put("pressure", np);
            targetState.getStats().put("trust", nt);
            if (np >= 100) {
                targetState.setActive(false);
                session.getGlobalEventLog().add("💥 压力爆表，该同事当场提桶跑路！");
            }
        }
        session.getGlobalEventLog().add("CARD_KEY:" + cardId);
        session.getGlobalEventLog().add(cardEffectDesc);
        if (comboMsg != null) session.getGlobalEventLog().add(comboMsg);

        // Trigger AI NPC autonomous reaction to this card!
        String promptMsg = "我对你打出了卡牌【" + cardEffectDesc + "】" + (note != null && !note.isBlank() ? ("，并附言：" + note) : "");
        Map<String, Object> aiResp = interact(sessionId, targetNpcId, promptMsg, null);
        return Map.of(
                "session", aiResp.get("session"),
                "decision", aiResp.get("decision"),
                "validation", aiResp.get("validation"),
                "cardLog", cardEffectDesc,
                "comboMsg", comboMsg == null ? "" : comboMsg
        );
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
                logMsg = "💸 经理发了 1000 元红包，目标同事压力 -25、信任 +15！";
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
