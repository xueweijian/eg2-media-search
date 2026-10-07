# 📋 Handoff v2.1：本地安卓多模态检索 App（EmbeddingGemma 2 + Gemma 4 E2B）

> v2.1 变更（2026-10-08）：**受众修正——非专属 App，面向广谱用户分发**。marble 从"目标机"降为"开发机/最低基线"；新增设备分级与模型路由（第 0 节）、模型分发渠道（第 2.5 节）、索引规模自适应（第 4 节）、低配降级（第 8 节）。
> v2 变更（2026-10-08 三轮头脑风暴定稿）：① 模型档位重构为**两模型制**（音频场景回归导致）② 五场景全做、全手机端（不走电脑预索引）③ 素材接入层/断点续跑/音频两级混合管线等遗漏补齐 ④ 新增**设计规范**节（学 AI Edge Gallery + Gemini app）⑤ NPU 通道终审。
> v1 的模型事实/基准/保命约束仍有效，本文为唯一权威版本，冲突处以本文为准。

---

## 0. 受众、设备矩阵与模型路由

**App 非专属，面向普通用户分发**。开发机 = Redmi Note 12 Turbo（marble，骁龙 7+ Gen 2 / SM7475-AB，Adreno 710，Android 15，~31GB 剩余）——恰好是"无 NPU 中端"的最差基线，性能下限的测试代表；上限靠设备分级吃满。

### 设备分级（运行时检测自动路由）

| Tier | 判定 | EG2 模型文件 | E2B | 预期 |
|---|---|---|---|---|
| 1 旗舰 | `Build.SOC_MODEL` ∈ {SM8550, SM8650, SM8750, SM8850, MT6991, MT6993, Tensor G5/G6...}（API 31+） | 对应 `_Qualcomm_SM8xxx.litertlm` 等 **NPU 编译版**（563/544MB） | SM8750+ 有 NPU 版；SM8550/8650 只有通用版 | 索引 TPU/NPU 级（~50ms/张） |
| 2 中端 | 不在 NPU 列表，`memoryClass ≥ 128` 且非 lowRam | 通用版 `embeddinggemma-2-740m.litertlm`（485MB，GPU/CPU delegate） | 通用版（可用，加载慢） | 200-400ms/张（marble 实测代表） |
| 3 低配 | `isLowRamDevice()` 或 RAM < 6GB 或 `memoryClass < 128` | 通用版 + **降批量索引** | **隐藏入口**（门控：仅当用户手动确认强开） | 索引多晚分批；E2B 大概率 OOM |

- 路由表 = `SOC_MODEL → 文件名` 硬编码映射 + 兜底通用版；检测失败/未知 SoC 一律通用版
- **E2B NPU 覆盖不对称（已实锤）**：E2B 编译版只有 sm8750/qcs8275/Tensor G5/G6/Intel；sm8550/sm8650（8 Gen 2/3）**无 E2B NPU 版**，走通用版
- GPU delegate 初始化失败 → CPU fallback（必做，Adreno/Mali 驱动碎片化）
- marble 开发机结论仍有效：自编译 QNN 不进 v1；勿跨片系套用 NPU 编译版

### 兼容性下限

- minSdk 以 tasks-retrieval 1.1.0 AAR 实际要求为准（开工第一周验证，预计 24-26）；targetSdk 35
- arm64-v8a 为主；armeabi-v7a 视 AAR 支持情况（lite 模型在 32 位机性能可能不可接受，可只发 arm64）

## 1. 任务与验收

| # | 场景 | 输入 | 输出 | LLM 需要？ |
|---|---|---|---|---|
| 1 | 文搜图 | 文本 query | 相册图片按相关度 | ❌ |
| 2 | 图搜图 | 图片/相机流 | 相似图片 | ❌ |
| 3 | 文搜视频 | 文本 query | 视频 + 精确时间戳 | ❌（画面问答时 E2B 按需） |
| 4 | 文搜音频 | 文本 query | 录音 + 时间戳片段 | ❌（点开片段出文字稿时 E2B 按需） |
| 5 | 文搜文档 | 文本 query | PDF/txt/电子书段落 | ❌ |

**核心哲学（用户定）**：检索永远零 LLM；Gemma 4 只在用户显式需要"生成/问答/转写"那一刻才加载，用完即杀。**全部手机端完成，不走电脑预索引。**

## 2. 两模型制（v2 核心修正）

| 模型 | 文件 | 大小 | 生命周期 |
|---|---|---|---|
| **EG2 740M 全量**（通用版） | `embeddinggemma-2-740m.litertlm` | 485MB | 常驻（前台保活），管五模态索引 + 全部查询 |
| **Gemma 4 E2B QAT int4** | `gemma-4-E2B-it.litertlm` | 2.0GB | **首发 APK 不带**，点按"AI 问答"才下载；独立 `:llm` 进程加载，用完杀进程 |

- 放弃 v1 的 270M/440M 多档制。原因：音频编码器只在 740M 档（litert-community **无 570M text+audio 档**，已实锤）；单档消灭全部跨档向量一致性风险；代价仅常驻 ~600MB（可接受）。
- E2B 内存实况（官方 QAT 表）：多模态 1.1GB / 纯文本 0.84GB。E4B(2.5GB)/12B 不考虑。
- 存储总账：485MB + 2.0GB + 向量库（2 万图 512d ≈ 40MB + 数百小时录音 ≈ 几十MB）≈ **2.6GB**。
- 权威事实（v1 已核验，仍有效）：768 维共享空间、MRL 截断 512d(-1.1%)/256d(-4.7%) 可用 128d 只配纯文本、8K token 共享预算（文本 8192 / 图 280token·~29 张 / 视频帧 140token·~58 帧 / 音频 25token·s ≈ 5.5min）、任务前缀（文本必加 `SearchQuery`/`Document`，媒体不加）、bf16/fp32 禁 fp16、截断后必须重新 L2 归一化、打分只看排名不用绝对阈值、Apache 2.0。

### 2.5 模型分发（多用户/国内网络现实）

- **下载源**：HuggingFace 国内直连不通。下载器按序回退：`hf-mirror.com`（HF 官方认可镜像，国内直连）→ 自托管 URL（可配置，GitHub Releases / R2 / 国内 OSS）→ huggingface.co（海外用户）
- 首启流程：装完 App 仅 ~APK 体积 → 引导页按设备分级路由自动选模型文件 → 下载 EG2（~485MB，显示进度+断点续传+sha256 校验）→ E2B 2.0GB 仅在用户点"AI 问答"时二次确认下载
- 校验与自愈：文件 hash 校验失败自动重下；LiteRT-LM 加载失败提示重新下载
- APK 分发双轨：GitHub Releases（直链 APK）为主；Play Store 可选后置（隐私声明"全本地零上传"是强卖点）。签名 + R8 混淆 + 版本化模型目录（升级模型文件 = 新文件名，老索引向量继续可用直到重建）

## 3. 技术栈（全官方）

| 层 | 方案 | 已核验要点 |
|---|---|---|
| 推理+检索 | `com.google.mediapipe:tasks-retrieval`（MediaPipe 1.1.0，传递依赖 LiteRT-LM） | `UniversalEmbedder`：embedText/embedImage/embedAudio/embedContent(List) + cosineSimilarity + setL2Normalize(true) + GPU delegate。**没有 embedVideo()**——视频须自建抽帧管线 |
| 向量库 | `SqliteVectorStore(context, "db名")` | SQLite 持久化；insertDocument（**大文档自动分块**）/insertImage(uri)/insertAudio(uri)/insertContent(List<Part>)/retrieve(query, topK)/delete(ids)；可选 AppSearchVectorStore（alpha，不用） |
| 文档 OCR | **ML Kit Text Recognition**（默认采纳；备选整页走 EG2 视觉塔） | 免费、轻量、中文准；有文字层的 PDF/截图走 OCR→文本塔，仅图表/扫描件 fallback 视觉塔 |
| 后台 | WorkManager | 充电+空闲约束 + 链式任务 + **SQLite 状态表断点续跑（幂等）** |
| UI | Jetpack Compose + Material 3 | 设计规范见第 6 节 |
| 构建 | GitHub Actions 公开仓库 CI（安卓 SDK/Gradle 不落手机沙箱，老规矩） | |

**参考源码（重点抄）**：github.com/google-ai-edge/gallery
- `customtasks/smartalbum/`（Instant Media Search：文搜图/图搜图/search-as-you-type）
- `customtasks/videomomentfinder/`（视频时间戳）
- `services/semanticretrieval/`（OnDeviceEmbedder.kt / SemanticRetrievalService.kt / GemmaEmbeddingModelStore.kt / EmbedPart.kt 核心服务层）

## 4. 五模态管线

**素材接入层（v1 遗漏，v2 补齐）**：
- 图片/视频：MediaStore（`READ_MEDIA_IMAGES/VIDEO`，处理 Android 15 "仅选中照片"细粒度权限）+ ContentObserver 增量入队
- 录音：**无系统统一库**——SAF 目录授权 + 手动导入 + 周期 diff
- 文档：SAF 文件选择器手动加入

**索引（全部后台、断点续跑，规模自适应）**：
- **索引规模自适应（多用户现实：1 千 ~ 5 万张不等）**：WorkManager 分批（每批 ~500 张）+ 多晚自动续跑直至清空队列；通知栏常驻进度（"已索引 N/M，预计剩余 X 分钟"）；用户可暂停/继续；电量温度护栏（温度过高自动暂停批）
- Tier 3 低配机降批量（每批更小 + 更长间隔），避免系统杀后台
- 图：缩放→视觉塔→512d 向量
- 视频：自建抽帧（MediaMetadataRetriever/MediaCodec；幻灯片类 1 帧/5-10s，动态类 1fps）+ 音轨拆出走音频线 → 双线入库各带时间戳。58 帧/窗上限，超长分窗
- 音频：16kHz mono（MediaExtractor+MediaCodec 解码重采样）→ 滑窗 **60-120s 窗 / 30-60s 步长**（勿用 5.5min 顶格）→ 音频塔 → 向量+时间戳区间
- 文档：有文字层→ML Kit OCR 抽文本→分块（`Document` 前缀 `title: none | text: {内容}`）→文本塔；图表/扫描件→整页渲染→视觉塔
- schema：每条记录 `{id, modality, source_uri, t_start, t_end, vector512}`；**时间戳在 ID/metadata 里自己管**（insertImage/insertAudio API 无时间戳字段）

**查询**：query 加 `SearchQuery` 前缀 → EG2 文本塔（8ms 级）→ ANN top-k（k=3~5）→ 合并相邻窗 → 排序展示 → **只对命中片段做重处理**

**音频两级混合（针对最大技术风险）**：
- 索引级：全部录音走 embedding 滑窗（便宜、可检索）。**绝不全量转写**（45min 课 E2B 转写 30min+，电量死）
- 展示级：点开命中片段 → E2B 按需转写那 1-2 分钟 → 缓存 SQLite
- 降级预案：中文 embedding 召回率不达标 → 重点录音用户显式触发全量转写建文本索引

## 5. 增强层（E2B 按需，全部渐进式）

1. **Caption-on-demand**：点某张图"AI 描述"才生成，缓存 SQLite，下次零成本
2. **重排不生成**：top-k 分数接近时 E2B 看图打分重排（输出几个 token，比生成省一个数量级）
3. **命中片段转写/问答**：视频关键帧+文 or 音频片段+问 → E2B（原生吃图/文/音；注意训练数据不含音乐/非语音声音事件）
4. 独立 `:llm` 进程 + 用完杀进程（内存立刻还系统）

## 6. 设计规范（用户点名学 AI Edge Gallery + Gemini app）

### 6.1 色彩（直接抄 gallery 源码 `ui/theme/Color.kt`，Apache-2.0 可抄）
- Light：primary `#0B57D0`（Google 品牌蓝）/ primaryContainer `#D3E3FD` / secondary `#00639B` / tertiary `#146C2E` / error `#B3261E` / surfaceContainer 阶梯 `#FFFFFF→#F8FAFD→#F0F4F9→#E9EEF6→#DDE3EA` / outline `#747775`
- Dark：primary `#A8C7FA` / background `#131314`（Google 近黑，非纯黑）/ surfaceContainer 阶梯 `#0E0E0E→#1B1B1B→#1E1F20→#282A2C→#333537`
- 完整 M3 colorScheme 双套映射（Theme.kt 现成代码）。跟系统深浅色，不做 dynamic color（保品牌一致性，同 gallery）

### 6.2 字体
- **Nunito**（gallery 同款，OFL 开源，8 字重 ExtraLight→Black）
- 中文回退 Noto Sans SC / 系统默认；大标题 homePageTitleStyle：48sp Medium letterSpacing -1sp

### 6.3 动效（Gemini 的"灵动自然"）
- **Material 3 Expressive**：spring 物理动画（Compose `spring()`，禁用生硬 tween）、大圆角、状态切换用 morph 形变
- **Search-as-you-type**：逐键实时重排结果（gallery 招牌交互，检索 8ms 天然支持）
- **Sparkle 加载动效**：索引/生成时四角星渐变动效（Gemini 标志语言），色彩用 Gemini 渐变 蓝→紫→粉（约 `#4285F4→#9B72CB→#D96570`），只用在"AI 时刻"（生成/问答/索引中），日常检索界面保持 Google 蓝纯净
- 预测性返回手势（predictive back）、edge-to-edge 布局

### 6.4 性能规范（"不卡顿"的工程保障）
- 推理永远在 IO/default dispatcher，主线程零接触；Compose + LazyVerticalGrid 虚拟化长列表
- 模型前台保活避免每次冷加载（740M 加载数秒）；查询路径 p95 < 100ms（编码 8-30ms + ANN 个位数 ms）
- 相机流检索丢帧策略：流式 embedding 用低分辨率，>100ms 自动降频

### 6.5 交互模式（v2 新增建议，均采纳）
- **索引进度可视化**：进度条 + "已索引 N/M 张 + 预计剩余"（索引风暴期间用户信任建设，WorkManager 进度天然支持）
- **时间轴标记**：视频/音频命中后在自定义 seekbar 上打标记点 + 底部 sheet 展示片段详情（Video Moments Finder 模式）
- **相似度分数展示**：结果卡显示 query→结果分数并提示"跨模态分数天然偏低，看排序即可"（教育用户，防"怎么才 0.4"疑惑）
- **空状态引导**：首次启动分步引导（授权相册→导入录音→加文档），每模态独立开关
- 长按多选批量操作；图片结果网格 + 列表双视图

## 7. 保命约束（v1 第 5/6 节精华，按官方栈修订）

1. 走 UniversalEmbedder task 时 resize/归一化/tokenize 已封装（`setL2Normalize(true)`）；**绕开官方 task 自己跑 .litertlm 推理时**才需要自己管 bf16/fp32（禁 fp16→静默 NaN）与截断后重归一化
2. 多模态索引 **512d**（存储紧张降 256d）；128d 只配纯文本；query 与库维度必须一致
3. 跨模态分数只看排名不用绝对阈值；一律 top-k + 合并 + 低置信回退
4. 长媒体滑窗索引记录时间戳（5.5min/58帧/29图是单次输入上限）
5. 首次建库=电量风暴：严格限充电+夜间+电量>40%；索引断点续跑幂等
6. EG2 不生成、不 ASR、不问答；复合输入用交织联合编码（`<|image|>` 占位），**禁向量加减法**
7. 视频最弱（MMEB Hit@1 50.67）：top-k + 可选 E2B 重排
8. 翻车预期管理：颜色词/细粒度属性（"黄花"≠yellow flower）是跨模态已知弱项，非 bug

## 8. 风险与开放问题

| 风险 | 等级 | 处置 |
|---|---|---|
| 中文音频 embedding 检索质量（MAEB 非中文中心） | 🔴 全项目最大 | 第一周自建评测（3 段真实课堂录音 × 10 query）；降级预案见 4 节 |
| Gemma 4 中文 ASR 质量未验证 | 🟡 | 同批实测；不达标则命中片段转写退回专用小 ASR（如 sherpa-onnx 系） |
| 270M/740M 跨档一致性 | 🟢 已消灭 | 两模型制下不存在 |
| 冷启动加载 | 🟢 | 前台保活 + 预热 |
| marble 无 NPU 索引慢 | 🟡 | 夜间分阶段跑（照片一夜/视频一夜），进度可视化 |
| 广谱设备兼容矩阵（SoC/Android 版本/GPU 驱动碎片化） | 🟡 | CI 矩阵测 + GPU→CPU fallback 必做；首发收窄到 arm64 + API 26+，按真实用户崩溃数据扩 |
| 低配机 E2B OOM / 后台被杀 | 🟡 | RAM 门控隐藏 E2B；索引批量化；前台服务保通知可见 |
| 国内模型下载失败 | 🔴 分发死穴 | hf-mirror 优先 + 自托管回退（见 2.5 节），首启引导内含下载诊断 |

## 9. 实施顺序（已与用户对齐）

1. **文搜图**：全抄 smartalbum，验证 90% 基础设施（MediaStore 接入/索引/WorkManager/SQLite/UI）
2. **图搜图**：同模型免费送（相机流 + 选图）
3. **文搜文档**：ML Kit OCR 线（便宜）+ 扫描件视觉塔 fallback
4. **文搜视频**：抽帧管线 + 时间戳 seekbar（工程最重）
5. **文搜音频**：滑窗 + 两级混合管线（风险最高，放最后但不砍）
6. E2B 增强层穿插：caption-on-demand → 片段转写 → 重排

## 10. 资源清单

- 模型卡：ai.google.dev/gemma/docs/embeddinggemma/model_card_2
- 开发者指南：developers.googleblog.com/embeddinggemma-2-the-developer-guide/
- 端侧落地：developers.googleblog.com/google-ai-edge-with-embeddinggemma-2/
- UniversalEmbedder Android：developers.google.com/edge/mediapipe/solutions/retrieval/universal_embedder/android
- SemanticRetriever Android：developers.google.com/edge/mediapipe/solutions/retrieval/semantic_retriever/android
- 权重：huggingface.co/google/embeddinggemma-2；量化包 huggingface.co/litert-community（EG2: embeddinggemma-2-740m-litert-lm；E2B: gemma-4-E2B-it-litert-lm）
- 官方 demo：github.com/google-ai-edge/gallery（smartalbum + videomomentfinder + services/semanticretrieval）
- 设计参照：gallery `ui/theme/`（Color/Theme/Type.kt 可直接抄）+ Gemini app（M3 Expressive/渐变/sparkle）
- Gemma 4：ai.google.dev/gemma/docs/core（内存表）

---

**一句话交接**：两模型制（EG2 740M 常驻管检索、E2B 按需管说话）、五模态全手机端、512d、零绝对阈值、MediaPipe 官方栈全封装、抄 gallery 架构与设计 token、学 Gemini 的 M3 Expressive 动效、索引夜间断点跑——**非专属 App：设备分级路由（旗舰吃 NPU 编译版/中端通用版/低配门控 E2B）+ hf-mirror 分发是国内用户跑通的第一道关**；最大技术风险是中文音频检索，第一周就测。
