package io.github.lumiagg.gestureevo.search

import android.content.Context
import androidx.core.net.toUri
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.db.getAPPDao
import io.github.lumiagg.gestureevo.search.config.SearchResult
import io.github.lumiagg.gestureevo.settings.ui.ShortcutResponse
import io.github.lumiagg.gestureevo.utils.PinyinUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.util.Locale

/**
 * Searches the bundled community app-shortcut catalog (the same data the
 * gesture shortcut picker uses) so quick search can surface entries such as
 * "QQ：扫一扫" next to the app itself. Only shortcuts whose host app is
 * currently installed are returned.
 */
object AppShortcutSearchHelper {

    private const val SHORTCUTS_ASSET = "shortcuts/other_user_shortcuts.json"
    private const val MAX_RESULTS = 12
    private const val MAX_PER_APP = 6

    private val normalizeRegex = Regex("[\\s\\n\\r\\t_\\-./\\\\]+")

    @Volatile
    private var cachedEntries: List<AppShortcutEntry>? = null

    private data class AppShortcutEntry(
        val shortcutName: String,
        val packageName: String,
        val targetUri: String,
        val nameTokens: List<String>
    )

    suspend fun search(context: Context, query: String): List<SearchResult.AppShortcutResult> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty()) return emptyList()
        val entries = loadEntries(context)
        if (entries.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val dao = getAPPDao()
            val appCache = HashMap<String, APPModel?>()
            val scored = ArrayList<Pair<Int, SearchResult.AppShortcutResult>>()
            val perAppCount = HashMap<String, Int>()
            for (entry in entries) {
                val app = appCache.getOrPut(entry.packageName) {
                    dao.findByPkg(entry.packageName)?.takeUnless { it.isRemove }
                } ?: continue
                val appMatched = listOf(app.name, app.pinyinFK, app.initialsFK)
                    .any { normalize(it).contains(normalizedQuery) }
                val shortcutMatched = entry.nameTokens.any { it.contains(normalizedQuery) }
                if (!appMatched && !shortcutMatched) continue
                val count = perAppCount.getOrDefault(entry.packageName, 0)
                if (count >= MAX_PER_APP) continue
                perAppCount[entry.packageName] = count + 1
                scored += (if (shortcutMatched) 0 else 1) to SearchResult.AppShortcutResult(
                    appName = app.name,
                    packageName = entry.packageName,
                    shortcutName = entry.shortcutName,
                    targetUri = entry.targetUri
                )
            }
            scored.sortedBy { it.first }.map { it.second }.take(MAX_RESULTS)
        }
    }

    private suspend fun loadEntries(context: Context): List<AppShortcutEntry> {
        cachedEntries?.let { return it }
        val entries = withContext(Dispatchers.IO) {
            try {
                val json = context.assets.open(SHORTCUTS_ASSET).bufferedReader().use { it.readText() }
                val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                val type = Types.newParameterizedType(List::class.java, ShortcutResponse::class.java)
                val responses = moshi.adapter<List<ShortcutResponse>>(type).fromJson(json).orEmpty()
                responses.mapNotNull { response ->
                    val name = response.name.trim()
                    if (name.isEmpty() || response.packageName.isBlank()) return@mapNotNull null
                    val target = extractTargetUri(response.scheme) ?: return@mapNotNull null
                    AppShortcutEntry(
                        shortcutName = name,
                        packageName = response.packageName,
                        targetUri = target,
                        nameTokens = listOf(
                            normalize(name),
                            normalize(PinyinUtils.getPinyinParse(name)),
                            normalize(PinyinUtils.getPinyinFP(name))
                        ).filter { it.isNotEmpty() }.distinct()
                    )
                }.distinctBy { Triple(it.packageName, it.shortcutName, it.targetUri) }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
        cachedEntries = entries
        return entries
    }

    // Bundled entries wrap the real target in a searchevo:// link whose
    // "url" query parameter holds the (encoded) deep link to open.
    private fun extractTargetUri(scheme: String): String? {
        val encoded = try {
            scheme.toUri().getQueryParameter("url")
        } catch (e: Exception) {
            null
        } ?: return null
        val target = try {
            URLDecoder.decode(encoded, "UTF-8")
        } catch (e: Exception) {
            encoded
        }.trim()
        if (target.isEmpty()) return null
        return if (target.contains("://") || target.startsWith("intent:")) target else null
    }

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(normalizeRegex, "")
}
