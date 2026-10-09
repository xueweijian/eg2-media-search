package com.xueweijian.eg2media.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.xueweijian.eg2media.core.AssetMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自定义资产/移除集持久化（v0.24，对齐官方 PhotoLibraryService.addCustomAssets/removeAssets）：
 * - custom_uris：photo picker 选入的 uri，takePersistableUriPermission 持久读授权
 *   （独立于系统部分授权集——官方"追加素材"的核心机制）
 * - removed_keys：被移除资产的 scopeKey（数字 id 或 "u"+uri），
 *   重新 addCustom 同一 uri 时自动恢复（官方 idsToUnremove 同款）
 *
 * 生效集 = MediaStore 可见集（过滤 removed）∪ custom（过滤 removed）
 */
object CustomAssetStore {
    private const val PREFS = "custom_assets"
    private const val KEY_URIS = "custom_uris"
    private const val KEY_REMOVED = "removed_keys"

    suspend fun addCustomUris(context: Context, uris: List<Uri>) = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext
        val ctx = context.applicationContext
        for (uri in uris) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } // 失败不阻断：部分 picker uri 本就无需持久化（一次性 grant 场景少见）
        }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_URIS, emptySet()) ?: emptySet()
        val updated = HashSet(current) + uris.map { it.toString() }
        // 官方语义：重新添加 = 从移除集恢复（id、uri 双形式都试）
        val removed = (prefs.getStringSet(KEY_REMOVED, emptySet()) ?: emptySet()).toMutableSet()
        val toUnremove = mutableSetOf<String>()
        for (u in uris.map { it.toString() }) {
            toUnremove.add(AssetMerge.customKey(u))
            toUnremove.add(u)
        }
        removed.removeAll(toUnremove)
        prefs.edit().putStringSet(KEY_URIS, updated).putStringSet(KEY_REMOVED, removed).apply()
    }

    fun customUris(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_URIS, emptySet()) ?: emptySet()

    fun removedKeys(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_REMOVED, emptySet()) ?: emptySet()

    /** 标记移除（不物理删除——重建/恢复语义友好），向量库清理由调用方 deleteBySource */
    fun markRemoved(context: Context, keys: Collection<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val removed = (prefs.getStringSet(KEY_REMOVED, emptySet()) ?: emptySet()) + keys
        prefs.edit().putStringSet(KEY_REMOVED, removed).apply()
    }

    /** 恢复全部已移除（"恢复已移除 N 项"菜单） */
    fun clearRemoved(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_REMOVED).apply()
    }

    /** 移除所有（清 custom + 全标 removed；向量库 deleteAll 由调用方） */
    fun markAllRemoved(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_URIS).remove(KEY_REMOVED).apply()
    }
}
