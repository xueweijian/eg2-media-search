# EG2 v0.24+ 方案：追加素材 / 搜索清除 / 视频栏 / 视频问答 Agent

状态：**讨论稿（未写代码）**——2026-10-09，基于官方 Edge Gallery 源码 + Ask Photos 研究 + 本机病灶确认。

---

## 0. Git 状态确认

- v0.23 = `e81e369`，已推送 origin/main，CI 双绿（build + e2e 8/8），Release 已发
- 真机已验证：图片搜索结果正确 ✅

---

## 1. 追加素材管理（抄官方，源码已吃透）

### 官方机制（SmartAlbumSearchScreen + PhotoLibraryService 实锤）

「管理」菜单四项：

| 菜单项 | 官方实现 |
|---|---|
| 添加更多照片 | `PickVisualMedia(ImageAndVideo)` 多选 → `createAssetFromUri` → **`takePersistableUriPermission`**（持久授权，独立于系统部分授权集）→ `addCustomAssets` 存 SharedPreferences → 刷新资产列表 → **worker 差集只索引新增** |
| 添加全部 N 张（条件显示） | `hasUnaddedPermittedMediaStoreAssets()` 检测「系统已授权但 app 未加入」→ 显示「添加全部 N 张」 |
| 选择要移除的照片 | `enterSelectionMode()` 多选模式 → `removeAssets(ids)` → removed_ids 持久化，查询时排除 + 清库 |
| 移除所有照片 | `removeAllPhotos()` → 清库 + 清 prefs |

关键设计：官方资产集 = **系统授权集 ∪ 自定义添加集 − 移除集**，三层各自持久化。追加的照片走 photo picker 的 per-uri 持久授权，**不依赖也不扰动系统部分授权**——这就是官方能随时追加的原因。

### 我们的实现路径

1. `CustomAssetStore`（SharedPreferences：`custom_uris` + `removed_ids`）
2. 资产查询 = MediaStore 可见集 ∪ custom − removed（图片/视频同规则）
3. 索引调度**零改动**：scheduleIndexing 差集幂等，追加自动只 embed 新的；移除走 `deleteBySource`，移除全部走 `deleteAll`
4. UI：照片流/视频流顶栏「管理」菜单（同官方四项）+ 长按进多选
5. 顺手收编 DiagScreen 里的「清空向量库」为正式功能

---

## 2. 搜索框清除体验（病灶 + 三层方案）

### 病灶（代码实锤）

`EG2App.kt:192` trailingIcon **只在 loading 时显示闪电，根本没有清除按钮**。你一直在用键盘逐字退格：
- 删「湖边」要点两次退格，每敲一下触发 120ms 防抖搜索重排，结果闪烁干扰手感
- 最后一字残留 = 退格节奏被搜索重排打断后的误触

### 方案（Google app / Google Photos 同款）

- **L1 一键清空**：非空时 trailing 显示 ✕（48dp 触区），单击 = 全清 + 保持键盘聚焦；loading 时换成进度圈（既是状态又是"稍等"暗示）
- **L2 防误触**：清空动作带 haptic；可选 SnackBar「已清除「湖边」·撤销」5 秒
- **L3 搜索历史 chips**：空查询时显示最近 5 条（点击重搜 / 长按删单条）——既解决重输成本，也兜底误删场景；Material 3 SearchBar 规范做法

推荐组合 = L1 + L3（Google app 完全同款），L2 视觉噪音大可先不做。

---

## 3. 视频栏改造（对齐图片栏）

现状：VideosScreen 只有搜索结果形态（有查询才有内容）→ 体验残缺。

改造：
- **无查询 = 全部视频网格**：loadThumbnail 缩略图 + 时长角标 + 播放角标，日期倒序（复用 GalleryScreen 骨架，素材规则与图片栏一致：∪custom−removed）
- **点击 = app 内预览播放**（VideoView；从搜索命中进入时起点 = 命中 mm:ss）
- **有查询 = 现有时间段命中卡**（HitMerge 设计保留，它是我们相对官方的差异化优势：官方只标"已分析"，我们能按时间片段命中）

---

## 4. 视频问答 Agent（Gemma 3n E2B 闭环）

### 底座（官方已趟平）

- 官方 `LlmChatModelHelper` 就跑 **Gemma-3n-E2B-it-int4**（allowlist 在案），图像输入 = `Content.ImageBytes(bitmap.toPngByteArray())`，vision 走 GPU（"must be GPU for Gemma 3n"）
- 与 HANDOFF 规划一致：E2B 2.0GB QAT int4，点按下载、独立进程用完杀——检索零 LLM，问答那一刻才加载

### 架构（Ask Photos 两阶段模式，实测交互最佳）

Google 官方披露的 Ask Photos 机制：**简单查询先秒出结果页；复杂问题后台继续深度工作**；用户可随时在经典结果与 AI 结果间切换。

我们照搬：
- **阶段 1（已有，秒级）**：query → embedding 检索 top-K 帧段 → 结果网格先出（不等待）
- **阶段 2（新增，流式）**：取 top 帧段关键帧（每段 1–2 帧，总 ≤6 帧，E2B 上下文预算内）→ E2B 多模态 prompt（帧 + 问题）→ 流式回答
- **引用（秘塔/NotebookLM 模式）**：回答内联 [1][2] 角标 → 底部来源卡（缩略图 + 视频名 + mm:ss）→ **点击角标/卡片跳视频对应秒播放**——引用必须可点、可验证，这是和"百度的 AI 摘要"的本质区别
- **入口形态**：搜索结果页顶部「AI 回答」卡片，默认折叠显示"正在观看 N 段视频…"（Ask Photos 式：先结果后答案，答案不阻塞检索）

### 现实约束（必须先对齐预期）

- E2B 在 marble（7+ Gen 2）预估 20–40 tok/s 生成 + 帧prefill 开销 → **答案限长（≤150 字）+ 可随时停止**
- 帧输入上限 6 帧 → 单次问答只"看"检索命中最强的片段；多轮追问可换帧
- 2.0GB 模型下载 = 首次点「问一问」时提示，Wi-Fi 建议
- 音频内容（说话内容）此期覆盖不了——那是音频模态 + ASR 的事，下一阶段

---

## 5. 优先级建议

| 级 | 项 | 理由 |
|---|---|---|
| P0 | 追加素材管理 | 官方对齐的最大功能缺口，且全部零件（差集索引）已就绪 |
| P0 | 搜索框清空 + 历史 chips | 每天都用的最痛交互 |
| P1 | 视频栏全量网格 + 预览 | 补齐对称体验，复用件多 |
| P2 | 视频问答 Agent | 大件：模型下载管线 + E2B 接入 + 引用 UI，建议独立一轮打磨 |

---

## 6. 待对齐的决策点

1. **清除交互**：A 纯 ✕ 清空 / B ✕ + 撤销 SnackBar / C ✕ + 历史 chips（我推荐 C，Google 同款，历史 chips 顺手把"重新搜上次词"也解决了）
2. **问答入口**：A 结果页顶部 AI 卡片（推荐，Ask Photos 式）/ B 独立「问」tab / C 全对话式
3. **E2B 下载时机**：首次点「问一问」提示下载（推荐）？还是设置页常驻下载卡？
4. **移除照片是否同步删向量库**：应该要（deleteBySource），确认无异议
5. **视频问答帧数预算**：6 帧/次起步，效果不够再权衡 prefill 时长
