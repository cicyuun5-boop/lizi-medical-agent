# AGENTS.md — 硅谷小智（医疗版）

本文件是**给 AI 编码代理的项目规则**。改本仓库任何代码前先读完本文件；本文件与代码冲突时，以代码为准并顺手改正本文件。

## 0. 一句话总览

一个医疗问诊智能体：**Vue3 前端单页聊天窗** ↔ **Spring Boot + LangChain4j 后端（流式 SSE）**，后端接阿里云百炼大模型，用 MySQL 存预约、MongoDB 存会话记忆、Pinecone 存知识库向量。

## 1. 项目地图

| 目录 | 内容 | 运行时读取？ |
|---|---|---|
| `后端代码/java-ai-langchain4j/` | Spring Boot 后端，Maven 单模块 | 是 |
| `前端代码/xiaozhi-ui/` | Vue3 + Vite 前端 | 是 |
| `后端代码/java-ai-langchain4j/src/main/resources/knowledge/` | RAG 知识库源文档（.md/.txt） | **是**（`ClassPathDocumentLoader` 读 `classpath:knowledge`） |
| `rag文档/` | 知识库资料副本 + PDF | **否**，只是资料备份 |
| `课件/` | 尚硅谷教学课件与图片 | 否 |

推论：**要改知识库，改 `resources/knowledge/`，不要改 `rag文档/`。**

## 2. 技术栈（实测版本，来自 pom.xml / package.json）

**后端**（`后端代码/java-ai-langchain4j/pom.xml`）

| 项 | 版本 |
|---|---|
| Java | 17 |
| Spring Boot | 3.2.6 |
| LangChain4j | 1.0.0-beta3 |
| MyBatis-Plus | 3.5.11 |
| Knife4j | 4.3.0 |
| 其他 | spring-boot-starter-web / webflux / data-mongodb、mysql-connector-j、langchain4j-easy-rag、langchain4j-pinecone、langchain4j-reactor、langchain4j-ollama、langchain4j-community-dashscope |

**前端**（`前端代码/xiaozhi-ui/package.json`）

| 项 | 版本 |
|---|---|
| Vue | 3.5.13（`<script setup>` SFC） |
| Vite | 5.4.8 |
| Element Plus | 2.8.4 |
| axios | 1.7.7 |
| uuid | 10.0.0 |

## 3. 命令

```bash
# 后端（在 后端代码/java-ai-langchain4j 下）
mvn -q spring-boot:run      # 启动，端口 8080
mvn test                    # 跑测试

# 前端（在 前端代码/xiaozhi-ui 下）
npm install
npm run dev                 # 开发服务器
npm run build               # 构建，改动后必须能通过
```

## 4. 运行前置（缺一个后端就起不来）

| 依赖 | 配置位置 | 说明 |
|---|---|---|
| MySQL `guiguxiaozhi` | `application.properties` | 建库建表用 `后端代码/java-ai-langchain4j/sql/create_database.sql` |
| MongoDB `chat_memory_db` | `application.properties` | `mongodb://localhost:27017/chat_memory_db`，存会话记忆 |
| 环境变量 `DASH_SCOPE_API_KEY` | 系统环境变量 | 百炼 API Key，`application.properties` 中以 `${DASH_SCOPE_API_KEY}` 引用 |
| 环境变量 `PINECONE_API_KEY` | 系统环境变量 | `EmbeddingStoreConfig` 用 `System.getenv("PINECONE_API_KEY")` 读取 |
| Ollama（可选） | `http://localhost:11434` | 仅配置了 chat-model，业务链路未使用 |
| Pinecone 索引 | `xiaozhi-index` / 命名空间 `xiaozhi-namespace` | 首次运行自动创建，AWS us-east-1 |

## 5. 硬约束（MUST / MUST NOT）

### 5.1 后端

1. **密钥一律走环境变量**，禁止把 API Key、数据库密码之外的真实密钥硬编码进代码或提交到仓库。
2. **改数据库结构必须同步改 `sql/create_database.sql`**，保持实体类字段与建表语句一致。禁止只改实体类让表结构漂移。
3. **`appointment` 表的唯一索引 `uk_appointment_user_slot(username, id_card, department, date, time)` 不能删。** `AppointmentService.getOne(Appointment)` 是 `selectOne` 语义，命中多行会抛 `TooManyResultsException`。
4. **`date` / `time` 是 String 类型**，不是 `DATE`/`TIME`。`time` 取值只有 `上午` / `下午`。
5. **实体入库前必须清空 `id`**（见 `AppointmentTools.bookAppointment` 的 `appointment.setId(null)`），防止大模型幻觉造出 id 导致覆盖已有记录。
6. **提示词放 `resources/*.txt` 并用 `@SystemMessage(fromResource = "...")` 引用**，不要把长中文提示词内联进 Java 字符串。现有映射：
   - `zhaozhi-prompt-template.txt` → `XiaozhiAgent`（主链路，医疗客服人设）
   - `my-prompt-template.txt` / `my-prompt-template3.txt` → `SeparateChatAssistant`（教学示例）
7. **`@AiService` 一律用 `wiringMode = EXPLICIT`**，显式声明 `chatModel` / `chatMemory` / `tools` / `contentRetriever` bean 名，不依赖自动装配。
8. **分层职责**：`controller` 只做参数转发，业务逻辑放 `service`；给大模型用的能力放 `tools`（`@Tool` 注解，描述文字会直接进 prompt，写清楚何时调用、调用顺序）。
9. **多轮会话必须带 `memoryId`**，记忆由 `chatMemoryProviderXiaozhi`（`maxMessages=20`）经 `MongoChatMemoryStore` 落到 MongoDB。新增对话接口时照抄这个模式。
10. **RAG 参数在 `XiaozhiAgentConfig`**：`maxResults=1`、`minScore=0.8`、切分 `recursive(300, 50)`。要调效果改这里，不要改知识库文档格式来凑。
11. **流式接口返回 `Flux<String>`**，`produces = "text/stream;charset=utf-8"`。不要改成阻塞式返回。

### 5.2 前端

1. **所有后端请求走 `/api` 前缀**（如 `/api/xiaozhi/chat`）。`vite.config.js` 的 proxy 会把 `/api` 转发到 `http://localhost:8080` 并**去掉 `/api`**。禁止在代码里写死 `http://localhost:8080`。
2. **流式解析固定用** `responseType: 'stream'` + `onDownloadProgress`，用 `e.event.target.responseText` **整体覆盖** `lastMsg.content`（XHR 的 `responseText` 恒为"截至当前的完整响应"）。**禁止改回按 `content.length` 做 substring 增量拼接**——转义会改变字符串长度（`<` → `&lt;`），长度基准必然错位，表现为消息重复或错乱。
3. **凡是走 `v-html` 渲染的消息内容，必须先过 `convertStreamOutput()`**，用户输入与模型输出两条路径都要过（`sendRequest` 里的 `userMsg.content` 与 `onDownloadProgress` 里的 `lastMsg.content`）。它是本项目唯一的转义出口，**新增任何 `v-html` 都必须复用它**，不要另写内联渲染。
4. **`convertStreamOutput` 内部顺序不可调换：先转义 `&`、`<`、`>`，再插入 `<br>`、`&nbsp;`。** 顺序反了会把刚插入的标记二次转义（`<br>` → `&lt;br&gt;`），换行和缩进直接显示成乱码文本——这是本项目历史上真实出现过的 bug。换行/缩进由它负责，后端只返回纯文本。
5. **`@` 别名指向 `src`**，import 用 `@/components/xxx.vue`，不要写 `../../` 相对路径。
6. **不新增前端依赖**。现有栈已够用；要用新库先确认 `Element Plus` / `axios` / `uuid` 无法覆盖。
7. **新增 UI 优先用 Element Plus 组件**（`el-button`、`el-input` 等），样式写在 `<style scoped>` 里，响应式断点沿用现有 768px 约定。

### 5.3 前后端契约

- 唯一对外接口：`POST /api/xiaozhi/chat` → 后端 `POST /xiaozhi/chat`
- 请求体（`bean/ChatForm.java`）：`{ "memoryId": <Long>, "message": "<String>" }`
- 响应：`text/stream` 纯文本流，前端增量拼接
- `memoryId` 由前端 `localStorage.user_uuid` 生成（`uuidToNumber`：取 UUID 前 6 位十六进制转数字后 `% 1000000`）。**改 `memoryId` 生成规则会切断老会话记忆**，需评估。

## 6. 已知技术债与有意简化（**不要当 bug 随手"修"**）

| 位置 | 现状 | 原因 / 升级路径 |
|---|---|---|
| `XiaozhiAgentConfig` 知识入库 | `xiaozhi.knowledge.ingest-on-startup` 默认 `true`，每次启动重新向量化写入 Pinecone，**会累积重复向量** | 已标 `ponytail:` 注释。首次入库成功后置为 `false`；彻底方案：给文档写稳定 documentId 做幂等覆盖 |
| `AppointmentTools.queryDepartment` | 未指定医生时**直接返回 `true`**（有号） | 项目没有医生排班表，判断不出号源容量；真实约束只有"同一医生同一时段不被重复占用"。升级路径：加 `doctor_schedule(department, doctor_name, date, time, capacity)` 表（已标 `ponytail:`） |
| `memoryId` 生成 | `% 1000000`，理论上有碰撞可能 | 教学项目取舍，未做全局唯一约束 |

## 7. 验收标准（改完必须自证）

1. **后端**：`mvn test` 通过。若改了数据库结构，先在干净 MySQL 上执行 `create_database.sql` 再启动验证。
2. **前端**：`npm run build` 通过。
3. **端到端**：`mvn spring-boot:run` + `npm run dev` 后，前端能完成一轮流式对话；涉及挂号的改动需实测「未知医生预约 → 重复预约被拒 → 取消预约」三条路径。
4. **非平凡逻辑**必须留下一个可运行的校验（一个断言式自检或一个小测试），不引入测试框架和 fixture。

## 8. 沟通约定

- 回答按 **根因 → 证据 → 方案 → 代价/风险** 顺序，只给最有效的 1–2 个方案，不列一堆并列选项。
- 区分事实与推断：**有把握的信息直接给**；**推断标注为推断**；不确定就说不确定，不装确定。
- 涉及版本、API、数字时给出可验证出处（文件路径 / 命令行 / 官方文档）。
- 依赖只做最小改动：优先复用现有工具类与模式，不新增抽象、不新增依赖、不加没人要的样板代码。
- 有意的简化必须留 `ponytail:` 注释，写明限制与升级路径。

## 9. Git 提交规范

仓库根目录是 `xiaozhi-medical`（**前后端同一个仓库**，不要在前端或后端子目录里单独 `git init`）。远程只有两个：`origin`（你自己的）和 `upstream`（原教学仓库）。

### 9.1 提交信息格式

沿用本仓库既有约定（见 `git log`）—— Conventional Commits + **中文正文**：

```
<type>: <中文主题，一句话说清这次做了什么>

<正文：根因 + 改动清单>
```

| 约束 | 要求 |
|---|---|
| `<type>` | 小写，取 `feat` / `fix` / `docs` / `chore` / `refactor` / `test` / `perf` / `build` |
| 主题行 | 中文，**不加句号**，必须具体。禁止 `fix: 修复bug`、`update`、`提交`、`更新代码` |
| 正文 | **必须写**，且只写「为什么」，不写「改了哪几个文件」 |
| 正文结构 | ① **根因**（一行说清问题出在哪）② **改动清单**（`-` 列表，每项一句）③ 有取舍的写明为什么不选另一种做法 |

示例（本仓库真实提交 `21502fd`）：主题 `feat: 新增建表 SQL，并把 queryDepartment 的空实现落到真实校验`，正文先写"原仓库只有实体类没有任何建表语句，启动必然报 Table doesn't exist"，再逐条列改动。

### 9.2 一次提交只做一件事

- 逻辑改动与格式化 / 重命名 / 文件移动**必须分开提交**。
- 前端修复、后端修复、文档新增是三类事，**不要塞进同一个 commit**。
- 判断标准：如果写不出一行干净的主题行，说明该拆成多个提交。

### 9.3 提交前必须自证（没过不许提交）

1. 按 **§7 验收标准**跑完对应检查：后端 `mvn test`、前端 `npm run build`、改了库结构则先在干净 MySQL 上执行 `sql/create_database.sql`。
2. `git status` 逐行看过，确认没有夹带无关文件。
3. 构建或测试没过，不许提交——不要靠"下个提交再修"。

### 9.4 绝对不要提交的东西

| 类别 | 具体 |
|---|---|
| 真实密钥 | `DASH_SCOPE_API_KEY` 等必须走**环境变量**，不得写进 `application.properties` |
| 本地覆盖文件 | `application-local.properties`、`.env`（`.gitignore` 已挡，**禁止 `git add -f` 强加**） |
| 构建产物 | `target/`、`node_modules/`、`dist/` |
| IDE / 系统文件 | `.idea/`、`.DS_Store`、`hs_err_pid*.log` |

> 历史教训：原仓库没有 `.gitignore` 时，**51 个不该入库的文件被提交**（33 个 `target/` 编译产物、11 个 `.DS_Store`、6 个 `.idea/` 配置、1 个 JVM 崩溃日志），后来才在 `cc8c926` 里清理。

### 9.5 推送纪律

- **只推 `origin`。禁止 `git push upstream`** —— 那是别人的教学仓库。
- **禁止对 `main` 使用 `git push --force`。**
- 当前只有 `main` 一个分支，改动直接在 `main` 上累积；需要试错时自建分支，不要用强制推送回退。
- 提交前 `git status` 看到未跟踪的构建产物，先用 `.gitignore` 挡住，而不是提交后再删。

### 9.6 为什么这些是硬约束而不是建议

本仓库**没有任何 git hook**（无 husky、`.git/hooks` 下无自定义脚本），提交信息格式与文件过滤**没有任何自动兜底**，全靠人和 AI 代理自觉执行。因此上述条目按 MUST / MUST NOT 对待。
