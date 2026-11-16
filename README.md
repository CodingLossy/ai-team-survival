# AI NPC Game Core Starter

两个参赛项目（《千万别散伙：AI 奇葩团队生存战》与《AI 测谎审讯室》）共用的 Java 17 + Spring Boot 3 后端核心骨架。

## 本地运行

```bash
export LLM_BASE_URL=https://api.deepseek.com/v1
export LLM_API_KEY=your_api_key_here
export LLM_MODEL=deepseek-chat
mvn spring-boot:run
```

服务默认监听 `7860` 端口（适配魔搭创空间 Docker 部署）。

## 核心接口
- `GET /api/health` — 健康检查
- `GET /api/levels` — 获取预设关卡列表
- `POST /api/levels/compile` — AI 一句话生成自定义关卡 JSON
- `POST /api/session/start` — 启动新对局
- `POST /api/session/interact` — 玩家交互（含多模态传感指标与规则校验闭环）
