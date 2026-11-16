package com.ainpc.core.controller;

import com.ainpc.core.engine.RuleValidationEngine;
import com.ainpc.core.model.GameSessionState;
import com.ainpc.core.model.LevelConfig;
import com.ainpc.core.service.GameSessionService;
import com.ainpc.core.service.LevelCompilerService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GameController {

    private final LevelCompilerService levelCompilerService;
    private final GameSessionService gameSessionService;

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/levels")
    public List<LevelConfig> listLevels() {
        return levelCompilerService.listLevels();
    }

    @PostMapping("/levels/compile")
    public LevelConfig compileLevel(@RequestBody CompileRequest req) {
        return levelCompilerService.compileCustomLevel(req.getGameType(), req.getIdea());
    }

    @PostMapping("/session/start")
    public GameSessionState startSession(@RequestParam String levelId) {
        return gameSessionService.startSession(levelId);
    }

    @PostMapping("/session/interact")
    public Map<String, Object> interact(@RequestBody InteractRequest req) {
        return gameSessionService.interact(
                req.getSessionId(),
                req.getTargetNpcId(),
                req.getMessage(),
                req.getSensors()
        );
    }

    @Data
    public static class CompileRequest {
        private LevelConfig.GameType gameType;
        private String idea;
    }

    @Data
    public static class InteractRequest {
        private String sessionId;
        private String targetNpcId;
        private String message;
        private RuleValidationEngine.SensorMetrics sensors;
    }

    @PostMapping("/session/action")
    public Map<String, Object> performAction(@RequestBody ActionRequest req) {
        return gameSessionService.performAction(
                req.getSessionId(),
                req.getActionType(),
                req.getTargetNpcId(),
                req.getCustomDeltas()
        );
    }

    @Data
    public static class ActionRequest {
        private String sessionId;
        private String actionType;
        private String targetNpcId;
        private Map<String, Integer> customDeltas;
    }
}
