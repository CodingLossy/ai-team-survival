package com.ainpc.core.engine;

import com.ainpc.core.model.GameSessionState;
import com.ainpc.core.model.LevelConfig;
import com.ainpc.core.model.NpcDecisionResponse;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class RuleValidationEngine {

    private static final int MAX_DELTA_PER_TURN = 35;

    @Data
    public static class SensorMetrics {
        private boolean blinkSpike;
        private double pauseSeconds;
        private boolean voiceTremor;
    }

    @Data
    public static class ValidationResult {
        private String executedAction;
        private boolean actionOverridden;
        private String overrideReason;
        private List<String> triggeredHooks = new ArrayList<>();
    }

    public ValidationResult validateAndApply(
            GameSessionState session,
            LevelConfig.NpcProfile profile,
            NpcDecisionResponse decision,
            SensorMetrics sensors) {

        ValidationResult result = new ValidationResult();
        LevelConfig cfg = session.getLevelConfig();
        GameSessionState.NpcRuntimeState npcState = session.getNpcStates().get(profile.getNpcId());

        int progressCap = (cfg.getGameType() == LevelConfig.GameType.TEAM_SURVIVAL && cfg.isHellMode()) ? 99 : 100;
        applyClampedDeltas(session.getGlobalStats(), decision.getGlobalStatDeltas(), 0, 100000, progressCap);
        applyClampedDeltas(npcState.getStats(), decision.getNpcStatDeltas(), 0, 100, 100);

        // Interrogation: sensor penalty + bsStreak nonsense penalty + bossRage
        if (cfg.getGameType() == LevelConfig.GameType.INTERROGATION) {
            if (sensors != null) {
                int sensorPenalty = 0;
                if (sensors.isBlinkSpike()) sensorPenalty += 8;
                if (sensors.getPauseSeconds() > 8.0) sensorPenalty += 5;
                if (sensors.isVoiceTremor()) sensorPenalty += 7;
                if (sensorPenalty > 0) {
                    int current = session.getGlobalStats().getOrDefault("suspicion", 0);
                    session.getGlobalStats().put("suspicion", Math.min(100, current + sensorPenalty));
                    session.getGlobalEventLog().add("传感器捕捉到紧张异常，怀疑度 +" + sensorPenalty);
                }
            }

            int bsStreak = session.getGlobalStats().getOrDefault("bsStreak", 0);
            int rage = session.getGlobalStats().getOrDefault("bossRage", 0);
            if (decision.isNonsense()) {
                bsStreak += 1;
                rage = Math.min(100, rage + 30);
                session.getGlobalStats().put("bsStreak", bsStreak);
                session.getGlobalStats().put("bossRage", rage);

                if (bsStreak >= 2) {
                    int sus = session.getGlobalStats().getOrDefault("suspicion", 0);
                    session.getGlobalStats().put("suspicion", Math.min(100, sus + 25));
                    int pat = npcState.getStats().getOrDefault("patience", 70);
                    npcState.getStats().put("patience", Math.max(0, pat - 20));
                    result.getTriggeredHooks().add("连续装傻/答非所问(" + bsStreak + "次)激怒审讯官，怀疑度强制 +25%！");
                } else if (rage >= 100) {
                    int sus = session.getGlobalStats().getOrDefault("suspicion", 0);
                    session.getGlobalStats().put("suspicion", Math.max(0, sus - 15));
                    session.getGlobalStats().put("bossRage", 20);
                    result.getTriggeredHooks().add("审讯官血压爆表彻底破防失态！趁乱怀疑度 -15%！");
                }
            } else {
                session.getGlobalStats().put("bsStreak", 0);
            }
        }

        String action = decision.getIntentAction();
        if (action == null || !profile.getAllowedActions().contains(action)) {
            action = profile.getAllowedActions().isEmpty() ? "IDLE" : profile.getAllowedActions().get(0);
            result.setActionOverridden(true);
            result.setOverrideReason("动作不在白名单内，已重置为默认动作");
        }

        if (cfg.getGameType() == LevelConfig.GameType.TEAM_SURVIVAL && cfg.isHellMode()) {
            int curProgress = session.getGlobalStats().getOrDefault("progress", 0);
            if ("FINAL_SIGN_OFF".equals(action)) {
                if (curProgress >= 85) {
                    session.getGlobalStats().put("progress", 100);
                    session.getGlobalEventLog().add("🎉 触发终极验收问答，成功突破 99% 魔咒达到 100%！");
                } else {
                    action = "OVERTIME_WORK";
                    result.setActionOverridden(true);
                    result.setOverrideReason("当前进度不足 85%，暂不能触发终极验收签字！");
                }
            } else if (curProgress >= 99) {
                session.getGlobalEventLog().add("⚠️ 进度卡在 99%！必须找到关键人物完成终极验收问答才能突破 100%！");
            }
        }

        if (cfg.getGameType() == LevelConfig.GameType.TEAM_SURVIVAL) {
            int pressure = npcState.getStats().getOrDefault("pressure", 0);
            if ("OVERTIME_WORK".equals(action) && pressure >= 90) {
                action = "SLACK_OFF";
                result.setActionOverridden(true);
                result.setOverrideReason(profile.getName() + "压力过大(>=90)，拒绝加班并转为摸鱼");
            }
            if (pressure >= 100) {
                action = "RESIGN";
                npcState.setActive(false);
                result.setActionOverridden(true);
                result.setOverrideReason(profile.getName() + "压力爆表(100)，触发强制离职！");
            }
        }

        if (cfg.getGameType() == LevelConfig.GameType.INTERROGATION) {
            boolean wasBluff = decision.isBluff() || "BLUFF_EVIDENCE".equals(action);
            session.getGlobalStats().put("lastWasBluff", wasBluff ? 1 : 0);
        }

        for (LevelConfig.RuleHook hook : cfg.getRuleHooks()) {
            Integer val = session.getGlobalStats().get(hook.getStatKey());
            if (val == null) val = npcState.getStats().get(hook.getStatKey());
            if (val != null && matches(val, hook.getOperator(), hook.getThreshold())) {
                action = hook.getForcedAction();
                result.getTriggeredHooks().add(hook.getDescription());
            }
        }

        result.setExecutedAction(action);

        if (decision.getRumorToShare() != null
                && decision.getRumorToShare().getTargetNpcId() != null
                && decision.getRumorToShare().getContent() != null) {
            GameSessionState.NpcRuntimeState target = session.getNpcStates()
                    .get(decision.getRumorToShare().getTargetNpcId());
            if (target != null && target.isActive()) {
                target.getPendingRumors().add(profile.getName() + "私下说：" + decision.getRumorToShare().getContent());
            }
        }

        evaluateEndState(session);
        return result;
    }

    private void applyClampedDeltas(Map<String, Integer> target, Map<String, Integer> deltas, int min, int max, int progressCap) {
        if (deltas == null) return;
        deltas.forEach((k, v) -> {
            if (v != null && target.containsKey(k)) {
                int clamped = Math.max(-MAX_DELTA_PER_TURN, Math.min(MAX_DELTA_PER_TURN, v));
                int cap = "progress".equals(k) ? progressCap : max;
                int next = Math.max(min, Math.min(cap, target.get(k) + clamped));
                target.put(k, next);
            }
        });
    }

    private boolean matches(int val, String op, int threshold) {
        return switch (op) {
            case ">=" -> val >= threshold;
            case "<=" -> val <= threshold;
            default -> false;
        };
    }

    public void evaluateEndState(GameSessionState session) {
        LevelConfig cfg = session.getLevelConfig();
        if (cfg.getGameType() == LevelConfig.GameType.INTERROGATION) {
            int suspicion = session.getGlobalStats().getOrDefault("suspicion", 0);
            if (suspicion >= 100) {
                session.setStatus(GameSessionState.SessionStatus.LOST);
                session.setEndReason("怀疑度达到 100，谎言被当场拆穿！");
            } else if (session.getCurrentTurn() >= cfg.getMaxTurns()) {
                session.setStatus(GameSessionState.SessionStatus.WON);
                session.setEndReason("成功撑过全部审讯回合，顺利过关！");
            }
        } else if (cfg.getGameType() == LevelConfig.GameType.TEAM_SURVIVAL) {
            int progress = session.getGlobalStats().getOrDefault("progress", 0);
            long activeCount = session.getNpcStates().values().stream().filter(GameSessionState.NpcRuntimeState::isActive).count();
            if (activeCount == 0) {
                session.setStatus(GameSessionState.SessionStatus.LOST);
                session.setEndReason("团队全员离职，项目彻底散伙！");
            } else if (progress >= 100) {
                session.setStatus(GameSessionState.SessionStatus.WON);
                session.setEndReason("项目顺利交付上线，团队成功存活！");
            } else if (session.getCurrentTurn() >= cfg.getMaxTurns()) {
                session.setStatus(GameSessionState.SessionStatus.LOST);
                session.setEndReason("Deadline 已到，项目进度停留在 " + progress + "%，未能上线！");
            }
        }
    }
}
