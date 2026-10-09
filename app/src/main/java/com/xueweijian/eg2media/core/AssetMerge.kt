package com.xueweijian.eg2media.core

/**
 * 资产三层合并（v0.24，对齐官方 Edge Gallery PhotoLibraryService 语义）：
 * 生效集 = 系统可见集 ∪ 自定义添加集（custom）− 移除集（removed）。
 *
 * key 规则（recordId sourceId / 差集 / removed 三处统一）：
 * - MediaStore 资产 = 纯数字 id（兼容既有索引库，不动）
 * - photo picker 自定义资产 = "u" + uri 字符串（非纯数字，天然不与 id 撞车）
 */
object AssetMerge {

    fun mediaKey(id: Long): String = id.toString()

    fun customKey(uri: String): String = "u$uri"

    /**
     * visible ∪ custom 去重：custom 条目若与 visible 同 key（photo picker 偶尔返回
     * MediaStore 形式 uri）则跳过——visible 优先（官方 shouldIncludeAsset 同语义：
     * 已存在的不再重复加入）。removed 过滤由调用方先行完成（两类 key 混在同一 Set）。
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
