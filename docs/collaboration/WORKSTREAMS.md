# 双仓库并行开发分工

## 1. 推荐分工

### 工作流 A：后端与 RAG-Core

负责人范围：

- Spring Boot 应用与模块边界
- MySQL Schema、Flyway 与固定知识库构建
- Problem Understanding
- SQL-first Retrieval Planner
- Qdrant 与 OpenAI 适配器
- 候选原因、证据、现场问题和报告 API
- OpenAPI 契约与后端测试

第一周交付：

1. 本地 MySQL/Qdrant 可启动
2. Flyway V1-V4 可执行
3. 固定 Excel 可导入
4. Problem Understanding API 返回结构化结果
5. Diagnosis API 先返回契约一致的 fixture

### 工作流 B：前端诊断工作台

负责人范围：

- React 页面结构与路由
- 出发前自然语言输入
- “AI 已理解”字段确认与 A/B/C 提示
- AI 分析动画
- 0–3 个候选原因和分类证据面板
- 现场单问题交互
- 报告预览和主动保存
- 响应式与中日文 UI

第一周交付：

1. 出发前分析完整页面
2. 使用 fixture 跑通输入、理解确认、分析动画与结果展示
3. 现场问题交互状态机
4. API Client 与错误、超时、空结果状态

## 2. 唯一协作契约

后端仓库的 `docs/api/openapi.yaml` 是唯一 API 事实来源。

规则：

1. 接口字段变更先修改 OpenAPI，再修改实现。
2. 前端不复制候选评分、信息门控或停止条件。
3. 后端不返回页面布局字段或 CSS 状态。
4. 枚举必须来自契约，禁止前后端各自定义中文字符串。
5. 每个接口至少维护成功、信息不足、证据不足和系统错误 fixture。

## 3. 每日合并点

每天只需要同步三件事：

- OpenAPI 是否变化
- fixture 是否变化
- 当前阻塞是否属于契约、数据还是 UI

不要用聊天消息临时约定字段；当天的决定必须落到 OpenAPI 或仓库文档。

## 4. 推荐开发顺序

```text
Day 1
后端：基础设施、Flyway、契约
前端：App Shell、路由、fixture

Day 2
后端：Excel 原始数据导入
前端：问题输入与理解确认

Day 3
后端：Problem Understanding + SQL 检索
前端：分析动画与候选结果

Day 4
后端：证据组装 + 向量兜底
前端：证据面板 + 现场问题

Day 5
共同：第一条端到端纵向案例
```

## 5. Definition of Done

一个功能只有同时满足以下条件才算完成：

- OpenAPI 已更新
- 后端测试通过
- 前端 fixture 与真实响应一致
- Loading、空结果和失败状态可操作
- 页面展示的原因和方案都有 Evidence Reference
- 不会在无证据时生成维修结论
