# EG2 Media Search（本地安卓多模态检索）

纯本地、隐私优先的安卓 App：用 **EmbeddingGemma 2（740M）** 在手机端建语义索引，
支持文搜图 / 图搜图 / 文搜视频 / 文搜音频 / 文搜文档。**全本地零上传**。

生成/问答能力（Gemma 4 E2B）按需加载，用完即杀——检索永远零 LLM。

## 状态

- [x] 项目脚手架 + CI（GitHub Actions 出 debug APK）
- [ ] Round 2：MediaPipe UniversalEmbedder + SemanticRetriever 接入
- [ ] Round 3：文搜图闭环（MediaStore 索引 + search-as-you-type）
- [ ] 图搜图 / 文档(ML Kit OCR) / 视频(时间戳) / 音频(滑窗+两级混合)

设计与架构总纲见 [HANDOFF.md](HANDOFF.md)（唯一权威文档）。

## 构建

无需本地 Android SDK——仓库自带 CI：

```
 Actions → android-ci → artifact: eg2-media-debug-apk
```

本地（如有环境）：`./gradlew assembleDebug`

## 技术栈

- Kotlin + Jetpack Compose + Material 3（设计 token 取自 google-ai-edge/gallery，Nunito 字体）
- MediaPipe `tasks-retrieval` 1.1.0（UniversalEmbedder + SemanticRetriever SqliteVectorStore）
- WorkManager（夜间充电断点索引）
- 设备分级路由：旗舰自动用 NPU 编译版模型，中低端通用版（HANDOFF §0）
- minSdk 26 / arm64-v8a / Apache-2.0 模型权重
