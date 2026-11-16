package com.ainpc.core.service;

import com.ainpc.core.llm.LlmClient;
import com.ainpc.core.model.LevelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class LevelCompilerService {

    private final ObjectMapper objectMapper;
    private final LlmClient llmClient;
    private final Map<String, LevelConfig> levelCatalog = new ConcurrentHashMap<>();

    @PostConstruct
    public void loadPresetLevels() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:levels/*.json");
            for (Resource res : resources) {
                LevelConfig cfg = objectMapper.readValue(res.getInputStream(), LevelConfig.class);
                levelCatalog.put(cfg.getLevelId(), cfg);
                log.info("Loaded preset level: {} ({})", cfg.getTitle(), cfg.getLevelId());
            }
        } catch (Exception e) {
            log.warn("No preset levels loaded: {}", e.getMessage());
        }
    }

    public List<LevelConfig> listLevels() {
        return new ArrayList<>(levelCatalog.values());
    }

    public LevelConfig getLevel(String levelId) {
        return levelCatalog.get(levelId);
    }

    public LevelConfig compileCustomLevel(LevelConfig.GameType gameType, String userIdea) {
        String systemPrompt = """
                你是一个 AI 游戏关卡编译器。根据玩家的一句话脑洞，严格输出符合以下结构的 JSON 对象（不要输出任何额外解释）：
                {
                  "levelId": "custom_xxx",
                  "gameType": "%s",
                  "title": "关卡标题",
                  "description": "一句话简介",
                  "maxTurns": 7,
                  "secretTruth": "如果是INTERROGATION填玩家秘密底牌，TEAM_SURVIVAL填项目背景",
                  "playerCards": ["给玩家看的3条简明已知底牌标签"],
                  "npcKnownFacts": ["给AI审讯官掌握的3条客观死证据"],
                  "globalStats": %s,
                  "npcs": [
                    {
                      "npcId": "npc_1",
                      "name": "角色名",
                      "role": "角色定位",
                      "avatarTag": "default",
                      "personaPrompt": "性格与说话风格",
                      "initialStats": %s,
                      "allowedActions": %s
                    }
                  ],
                  "ruleHooks": []
                }
                """.formatted(
                gameType.name(),
                gameType == LevelConfig.GameType.INTERROGATION ? "{\"suspicion\": 20}" : "{\"progress\": 0, \"budget\": 10000}",
                gameType == LevelConfig.GameType.INTERROGATION ? "{\"patience\": 70, \"trust\": 30}" : "{\"trust\": 50, \"pressure\": 20}",
                gameType == LevelConfig.GameType.INTERROGATION ? "[\"ASK_DETAIL\", \"BLUFF_EVIDENCE\", \"OPEN_BAG_CHECK\"]" : "[\"OVERTIME_WORK\", \"SLACK_OFF\", \"GOSSIP_IN_GROUP\", \"RESIGN\"]"
        );

        LevelConfig compiled = llmClient.chatJson(systemPrompt, userIdea, LevelConfig.class);
        compiled.setLevelId("custom_" + UUID.randomUUID().toString().substring(0, 8));
        compiled.setGameType(gameType);
        levelCatalog.put(compiled.getLevelId(), compiled);
        return compiled;
    }
}
