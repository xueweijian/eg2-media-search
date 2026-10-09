package com.xueweijian.eg2media.core

/**
 * 资产三层合并（v0.24，对齐官方 Edge Gallery PhotoLibraryService 语义）：
 * 生效集 = 系统可见集 ∪ 自定义添加集（custom）− 移除集（removed）。
 *
 * key 规则（recordId sourceId / 差集 / removed 三处统一）：
 * - MediaStore 资产 = 纯数字 id（兼容既有索引库，不动）
 * - photo picker 自定义资产 = "u" + uri 字符串（非纯数字，天然不与 id 撞车）
 * - v0.24.1：picker 偶尔返回 MediaStore 形式 uri（content://media/external/
 *   {images|video}/media/{id}）——归一到数字 id 命名空间，与可见集同 key 天然去重，
 *   否则同一照片双展示 + 双入库（Round 26.5 连带发现）
 */
object AssetMerge {

    fun mediaKey(id: Long): String = id.toString()

    fun customKey(uri: String): String = "u$uri"

    /** MediaStore 形式 uri 识别（picker 对已可见资产会返回此形式）：末段数字 id */
    fun mediaStoreUriId(uri: String): Long? {
        val m = MEDIA_STORE_URI.find(uri) ?: return null
        return m.groupValues[1].toLongOrNull()
    }

    /** custom 资产归一 scopeKey：MediaStore 形式→数字 id（并入可见集命名空间），否则 "u"+uri */
    fun normalizedCustomKey(uri: String): String = mediaStoreUriId(uri)?.toString() ?: customKey(uri)

    private val MEDIA_STORE_URI = Regex("^content://media/external/(?:images|video)/media/(\\d+)$")

    /**
     * visible ∪ custom 去重：custom 条目若与 visible 同 key 则跳过——visible 优先
     * （官方 shouldIncludeAsset 同语义：已存在的不再重复加入）。
     * v0.24.1：调用方须用 AssetMerge.normalizedCustomKey 归一 custom 的 key，
     * 否则 MediaStore 形式的 custom uri（数字 id）与可见集（"u"+uri）两命名空间
     * 永不相等，去重为 no-op（Round 26.5 连带 bug 实锤）。
     * removed 过滤由调用方先行完成（两类 key 混在同一 Set）。
     */
    fun <T> merge(
        visible: List<T>,
        custom: List<T>,
        keyOf: (T) -> String,
    ): List<T> {
        if (custom.isEmpty()) return visible
        val visibleKeys = visible.map(keyOf).toHashSet()
        return visible + custom.filter { keyOf(it) !in visibleKeys }
    }

    /**
     * 从索引库 recordId 全集（"src|mod|t0-t1"）提取已索引资产 key 集，
     * 供差集幂等判断（追加素材后只 embed 新项）。
     */
    fun indexedAssetKeys(recordIds: Set<String>): Set<String> =
        recordIds.asSequence().map { it.substringBefore('|') }.toSet()
}
