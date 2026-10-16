# OpenAI 模型费用统计

- `V22__add_model_call_cost.sql` 新增调用明细表，启动时由 Flyway 执行。旧调用无法补算。
- `application.yml` 的 `repair-assistant.model-pricing.models` 保存精确模型名称对应的美元单价；更换模型时同步配置对应单价。不会用默认模型单价替代未知模型。
- `input`、`cached-input`、`audio-input`、`output` 单位为美元／百万 token。`per-minute` 为美元／分钟。`estimated-per-minute` 仅用于无完整 token 用量时的音频估算。当前采用标准 API 单价，不含税费、特殊区域附加费或账户折扣。
- 文字用量读取响应 `usage`，缓存输入从普通输入中扣除；`output_tokens` 已包含推理 token，不重复添加。诊断向量检索也记费。
- 实时转写保存 completed 事件中的 token 用量，但按时长计费；优先使用返回的 `usage.seconds`，否则按已提交 PCM 片段时长估算并标记 ESTIMATED。重复 completed 事件使用会话＋item ID 去重。
- 说话人分离优先使用音频／文本 token 单价；`diarized_json` 缺少用量时按时长参考单价估算，窗口重叠和实际重试分别累计，不按原文件总时长去重。
- 费用在解析业务输出前保存，并使用独立事务，避免后续业务失败抹掉已产生的调用费用。无响应、缺少用量或未知单价的调用保留待核算记录，不记为免费。
- 全部费用＝所有已知费用；电话系统场景参考＝全部费用－说话人分离－角色识别。存在待核算调用时，页面提示合计不完整。该参考不改变当前实际执行流程。
- 录音调用按 batch key 归集。文字分析请求通过 `X-Model-Cost-Scope` 传递工作标识；前端将标识绑定到理解／诊断 ID，并在当前标签页保存，继续现场分析时沿用。独立文字分析使用独立工作标识，选择新示例时新建。
- `GET /api/v1/model-costs/{scope}` 返回两种总额、两项独立费用及调用明细和单价快照；页面约四秒刷新一次。费用持久化后不受后续单价修改影响。
- 只统计 OpenAI。MOSS 转写费用未纳入；服务器、电话系统、存储费用也未纳入。本功能计算的是 API 成本参考，最终以供应商账单为准。

官方接口与计价参考：
- https://developers.openai.com/api/docs/models/gpt-live-transcribe
- https://developers.openai.com/api/docs/models/gpt-5-mini
- https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create

## 流程历史（V23）

浮动历史按最近20个流程展示“时间／功能／日元花费”。业务步骤成功结束时发布该功能的累计快照：实时转写、设备信息提取（含型号与错误码匹配）、问题理解、故障诊断分析（含检索向量）。规则完成但未调用模型的步骤为零费用。处理中调用仍写入原始明细，但不会提前更新历史快照。

诊断成功完成时发布整个 scope 的总计快照，包含从录音或出发前分析开始的所有已发生调用，也包含失败／重试所产生的费用，排除分离和角色识别。重新分析完成时更新同一流程的快照，不重复新增整条流程。总计是最近一次完成诊断时的累计费用，不是各次总计相加。

V23之前没有步骤结束标记，无法可靠还原历史步骤；此展示从更新后完成的步骤开始记录。原始费用明细仍保留。
