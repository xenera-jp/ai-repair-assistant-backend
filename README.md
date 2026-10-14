# AI Repair Assistant Backend

Spring Boot backend and RAG-Core for the AI repair assistant.

## 实时录音 DEMO

录音上传只保存和准备播放。点击“开始演示”后按原速发送同源 PCM，实时 WebSocket 显示草稿，同时用 `gpt-4o-transcribe-diarize` 处理已播放窗口，异步形成带时间和说话人的确认对话。两路收尾后沿用现有聊天模型识别业务角色；点击“对话总结”才开始原有设备信息提取。

- 实时转写配置加入 FH1-AAC、FH1-SSB、RIR1-SSB、HNC-120AA 以及已审查的报警码 prompt/keywords（中文/日文）。提示不要求型号和错误码在同一句出现，也不允许补充未说出的标识符。
- 确认对话以分离模型的文字、说话人和时间为主体；缓存实时 completed 及音频时间，通过纯后端规则局部保护型号/错误码。实时值经大小写、全角、分隔符及逐字母/英文数字拼读规范化后命中本会话设备字典；目标字段明确且时间重叠时，允许同一个唯一实时候选保护多条分离结果和重复字段，单个型号字段优先采用实时值。否定/纠正、序列号、测量值、多个实时候选及同一行明确冲突的多个型号不自动覆盖。支持 E・1、イーチ 及“1というのが点滅”的错误码字段；仅合并有实时依据的相邻同型号字段和同错误码点滅重复片段，不清理普通正文重复。未提到型号时，错误码可按共享字典校验；已有唯一实时型号时仍校验其对应错误码。
- 分离先返回或实时先返回均会重算局部保护，始终以原始分离文本为输入；不拆分普通文字、不改变说话人或时间。原始分离结果仍保存在窗口 result_json，实时候选和替换记录写入本会话最近完成窗口的 identifierProtection，收尾时补写最终记录。
- 窗口局部重叠不会删除完整旧发言；只有完整覆盖且包含旧文字的结果才能替换旧片段。
- 实时转写使用 `OPENAI_TRANSCRIPTION_MODEL`（默认 `gpt-live-transcribe`）；分离独立使用 `OPENAI_DIARIZATION_MODEL`（默认 `gpt-4o-transcribe-diarize`）；角色识别继续使用 `OPENAI_CHAT_MODEL`。
- 部署环境如果仍配置文件 diarize 模型，需要将 `OPENAI_TRANSCRIPTION_MODEL` 配置为支持实时转写的模型并重启后端。代码不替换配置值，不修改持久化环境文件。
- 启动时执行 V19 和 V20 迁移；V20 保存窗口标识、时间、重叠、版本、重试状态及局部模型结果。上传、恢复和重试均不会调用整文件转写。
- 浏览器在前端解码录音文件，按播放位置发送 24kHz 单声道 PCM；文件实时模拟支持 HTTP，无需 AudioWorklet 或麦克风权限。跨源音频仍需正确的 CORS；首版禁止实时模式拖动和倍速。开始前需读取并解码完整文件，长录音会增加准备时间和浏览器内存占用。
- 前端复用已下载文件的 Blob 地址播放，避免重复网络读取；音频帧仍为100毫秒，已播放帧每次最多5帧批量提交，传输缓冲最多约2秒。短暂网络延迟通过批量发送追赶，后端转写或分离积压仍会暂停并自动续播。批量接口为 `/recording-realtime-sessions/{id}/frame-batches`，需前后端一起部署。
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
