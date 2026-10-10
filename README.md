# AI Repair Assistant Backend

Spring Boot backend and RAG-Core for the AI repair assistant.

## 实时录音 DEMO

录音上传只保存和准备播放。点击“开始演示”后按原速发送同源 PCM，实时 WebSocket 显示草稿，同时用 `gpt-4o-transcribe-diarize` 处理已播放窗口，异步形成带时间和说话人的确认对话。两路收尾后沿用现有聊天模型识别业务角色；点击“对话总结”才开始原有设备信息提取。

- 实时转写使用 `OPENAI_TRANSCRIPTION_MODEL`（默认 `gpt-live-transcribe`）；分离独立使用 `OPENAI_DIARIZATION_MODEL`（默认 `gpt-4o-transcribe-diarize`）；角色识别继续使用 `OPENAI_CHAT_MODEL`。
- 部署环境如果仍配置文件 diarize 模型，需要将 `OPENAI_TRANSCRIPTION_MODEL` 配置为支持实时转写的模型并重启后端。代码不替换配置值，不修改持久化环境文件。
- 启动时执行 V19 和 V20 迁移；V20 保存窗口标识、时间、重叠、版本、重试状态及局部模型结果。上传、恢复和重试均不会调用整文件转写。
- 浏览器需要 HTTPS 或 localhost、AudioWorklet 和正确的跨源音频 CORS；首版禁止实时模式拖动和倍速。
- 窗口目标 12 秒，最长 15 秒，重叠 3 秒；每个会话按源时间串行处理，最多积压 3 窗，达到限制同时暂停播放和采集。DEMO 最长两小时、最多 10000 个确认片段。
- A/B 通过模型结果的重叠时间与文字关联；可从清晰片段选取 2～10 秒参考音频。缺少可靠共同片段时保留未知，额外说话人不强行并入 A/B。参考只在本次会话内保存。
- 窗口失败自动最多重试 3 次，再禁用总结并提供“重试说话人分离”；普通暂停保持会话，连接失效或空闲五分钟需要重头演示。重启后不透明续接，不提供失败范围的自动部分总结。
- 总结请求携带 `conversationVersion`，后端在事务内校验并原子防重复。总结后禁止修改来源说话人和角色，需新建演示重新生成。

详细设计：[实时转写与 OpenAI 说话人分离方案](../docs/docs/ai-repair-assistant-realtime-transcription-rule-speaker-demo-v1.md)。真实模型账户可用性、电话音频效果和延迟仍需联调。

This V1 implements the complete pre-departure diagnosis path:

1. Import the fixed Excel knowledge pack.
2. Build structured maintenance cases in MySQL.
3. Generate 512-dimensional OpenAI embeddings and index them in Qdrant.
4. Interpret a natural-language maintenance problem.
5. Prefer deterministic SQL retrieval, then use vector retrieval as fallback.
6. Return up to three causes with evidence, parts, tools and repair steps.

## Stack

- Java 21
- Spring Boot 4.1
- MySQL 8.4
- Qdrant 1.18
- OpenAI
- Flyway

## Local start

Place `OPENAI_API_KEY` in `.env.local` in this repository or its parent
directory. The key is never committed.

```bash
cp .env.example .env.local
docker compose up -d
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw spring-boot:run
```

On the first start, Flyway creates the MySQL schema and the importer loads the
three workbooks under `data/knowledge`. Repeated starts are idempotent.

Backend:

```text
http://localhost:8080
GET /api/v1/system/status
GET /actuator/health
POST /api/v1/problem-understandings
POST /api/v1/diagnosis-sessions
```

Default local infrastructure:

- MySQL: `localhost:3307`
- Qdrant REST: `localhost:6333`
- Qdrant gRPC: `localhost:6334`

Demo input:

```text
RIR1-SSB 冷却效果明显下降，背面发热，显示 E4。设备仍在运行，但柜内温度持续升高。
```

## Repository ownership

- This repository owns domain rules, database migrations, knowledge construction,
  retrieval planning, OpenAI/Qdrant adapters and the OpenAPI contract.
- API changes start in `docs/api/openapi.yaml`.
- The frontend repository consumes the contract and must not duplicate diagnosis rules.

See [the collaboration workstreams](docs/collaboration/WORKSTREAMS.md).

For the complete server-side execution path, data layers, scoring rules and
extension points, see [the server implementation guide](docs/SERVER_IMPLEMENTATION_GUIDE.md).
