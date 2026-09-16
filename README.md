# Chaos Quest

Chaos Quest 是一个下班后生活行动启动器：把“想做点什么”变成一张可以立刻开始、当天完成的轻量任务。

核心规则：任意时刻只有 1 个进行中的任务；完成获得 XP；未完成任务次日自动注销，不视为完成，也不产生惩罚。

## 主要能力

- 轻支线、混沌支线、Boss 任务：由内置任务蓝图生成。
- AI 生成：用户可输入主题，也可随机生成；AI 返回可执行的任务、分类和预计时长。
- 预计时长：按任务实际内容估算 5–30 分钟。简单呼吸、问候或记录可为 5–10 分钟，整理与规划按工作量安排。
- 执行计时：可选倒计时；运行期间展示系统通知，到时提醒用户通关。
- XP：等于任务的预计分钟数，提前完成也不扣减。
- 轻量偏好学习：用户主动开启后，根据已完成、重掷和归档行为调整随机推荐；可随时查看、采纳或清除。

## 项目架构

```text
Compose UI
  QuestScreen ── QuestViewModel
                         │
                         ▼
                   QuestRepository
       ┌─────────────────┼─────────────────┐
       ▼                 ▼                 ▼
任务蓝图与状态        AI 任务生成        计时与通知
data/                ai/                timer/
QuestBlueprintPlanner TaskAiClient       QuestTimerController
QuestStateStore      OpenAiApiTaskGenerator QuestTimerService
QuestDuration
       │                 │                 │
       └─────────────────┼─────────────────┘
                         ▼
                 轻量偏好学习
                 preference/
       LocalPreferenceRepository + RuleEngine
       AiPreferenceInferenceEngine + RecommendationPolicy
                         │
                         ▼
                 本地持久化（SharedPreferences）

App Functions（functions/）复用 QuestRepository，
日志（logging/AppLog）使用统一的应用 Tag。
```

### 模块职责

| 模块 | 职责 |
| --- | --- |
| `ui/` | Compose 页面、交互状态、加载提示和 ViewModel。 |
| `data/` | 任务实体、任务蓝图、单任务状态机、XP、日切注销和本地任务状态。 |
| `ai/` | OpenAI 兼容接口调用、提示词、结构化响应校验与失败回退。 |
| `timer/` | 倒计时状态、前台通知、到时提醒和服务恢复。 |
| `preference/` | 用户授权后的行为记录、规则权重、偏好摘要及推荐策略。 |
| `functions/` | 对外暴露的应用能力，直接调用同一套任务仓库。 |
| `logging/` | 统一的应用日志入口，便于按应用 Tag 筛选。 |

`QuestRepository` 是任务生命周期唯一入口：创建、重掷、开始、完成、归档和次日注销都在这里串行执行。它负责同时更新任务状态、计时、XP 与偏好事件，避免出现多个进行中任务或状态不一致。

## 普通任务流程示例

用户点击“抽轻支线”：

```text
QuestScreen
  → QuestViewModel.rollQuest(chill)
  → QuestRepository.rollQuest(...)
  → PreferencePolicy（可选：给出适合的主题 / 强度 / 时长倾向）
  → QuestBlueprintPlanner（选择任务原型并按内容估时）
  → QuestStateStore（保存唯一进行中任务）
  → QuestScreen 展示任务卡
```

示例结果：

```text
任务：小型仪式 · 呼吸计时
内容：闭眼专注呼吸，默数 20 次吸气和呼气。
预计：10 分钟
XP：10
```

用户点击“开始执行”后，`QuestTimerController` 按 10 分钟创建倒计时并启动通知；点击“通关”后，`QuestRepository.completeQuest` 发放 10 XP、清理计时和进行中任务，并记录一次完成行为。用户点击“再来一个”时只替换当前任务，不会新增队列。

## AI 任务流程示例

用户在“AI 生成”中输入“收拾衣柜”：

```text
QuestScreen 输入主题
  → QuestViewModel.rollQuestWithApi(theme = "收拾衣柜")
  → QuestRepository.rollQuestWithApi(...)
  → PreferencePolicy
       明确主题优先；偏好只提供辅助提示
  → QuestBlueprintPlanner
       生成安全的初始蓝图和参考时长
  → OpenAiApiTaskGenerator
       请求 AI 生成 title / description / reward / topic / durationMinutes
  → 响应校验
       标题、描述、主题和 5–30 分钟时长必须有效
  → QuestRepository 创建并保存任务
  → QuestScreen 展示任务卡
```

示例结果：

```text
任务：衣柜上层清场
内容：只处理衣柜最上层。拿出不适合当季的衣物，留下要保留的，
把其余衣物放入一个待处理袋；完成后拍一张整理后的局部。
预计：15 分钟
XP：15
分类：生活整理
```

AI 不可用、网络异常或返回内容不合法时，流程自动回退到同主题的任务蓝图，用户仍能立即得到一张可执行任务。重掷 AI 任务会保留原主题和入口类型，再次生成并替换当前任务。

## 本地配置

将接口配置放在根目录 `local.properties`：

```properties
taskflowApiBaseUrl=https://<host>/v1
taskflowApiKey=<your-api-key>
taskflowApiModel=<model-name>
```

该文件不应提交。未配置或调用失败时，AI 生成会自动使用本地任务蓝图回退。

## 验证

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --offline
```

单元测试覆盖任务时长、XP 对齐、短任务偏好、偏好行为去重与失效数据清理。
