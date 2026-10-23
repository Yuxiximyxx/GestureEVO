package io.github.lumiagg.gestureevo.search

import androidx.core.graphics.drawable.toDrawable

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.datastore.preferences.core.Preferences
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityQuickSearchBinding
import io.github.lumiagg.gestureevo.databinding.RecentAppItemBinding
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.config.IconType
import io.github.lumiagg.gestureevo.gesture.ui.RoundImageTextView
import io.github.lumiagg.gestureevo.search.config.EngineType
import io.github.lumiagg.gestureevo.search.config.SearchEngine
import io.github.lumiagg.gestureevo.search.config.SearchEngineDataManager
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.ClipboardUtils
import io.github.lumiagg.gestureevo.utils.RecentAppsRecorder
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.uriBuilder
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.util.WeakHashMap

class SearchEngineManager(
    private val activity: QuickSearchActivity,
    private val binding: ActivityQuickSearchBinding,
) {
    private val uiDispatcher = Dispatchers.Main.immediate
    private val ioDispatcher = Dispatchers.IO

    private var currentSearchEngine: SearchEngine? = null
    private val searchEngines = mutableListOf<SearchEngine>()
    private var enginePager: QuickSearchEnginePager? = null
    private var recentApps: List<APPModel> = emptyList()
    private val recentAppsAdapter = RecentAppsAdapter()
    private var showImageBadge = true
    private var bottomInputLayoutEnabled = false
    private val iconBindings = WeakHashMap<RoundImageTextView, Any>()
    private var engineDataLoaded = false
    private var pendingSearch: Pair<String, Boolean>? = null

    private val lifecycleScope: LifecycleCoroutineScope
        get() = activity.lifecycleScope

    fun initialize() = lifecycleScope.launch(uiDispatcher) {
        initEngineRecycler()
        initEngineData()
    }

    suspend fun initEngineData() {
        val (engines, defaultEngineTag) = withContext(ioDispatcher) {
            Pair(
                SearchEngineDataManager.readSearchEngineList(activity.applicationContext)
                    .filter { it.showInSearchEngineList },
                SearchEngineDataManager.getDefaultEngineTag(activity.applicationContext)
            )
        }
        searchEngines.clear()
        searchEngines.addAll(engines)
        currentSearchEngine = engines.find { it.tag == defaultEngineTag } ?: engines.firstOrNull()
        engineDataLoaded = true
        updateSearchEngineIcon()
        loadRecentApps()
        pendingSearch?.let { (query, freeform) ->
            pendingSearch = null
            performSearchByCurrentEngine(query, freeform)
        }
    }

    suspend fun initEngineRecycler(snapshot: Preferences? = null) {
        val preferences = snapshot ?: activity.settingsStore.data.first()
        configureEngineRecycler(preferences)
    }

    fun configureEngineRecycler(preferences: Preferences) {
        bottomInputLayoutEnabled = preferences.readKey(
            SearchEngineSettingsActivity.KEY_BOTTOM_INPUT_LAYOUT,
            false
        )
        showImageBadge = preferences.readKey(
            SearchEngineSettingsActivity.KEY_SHOW_IMAGE_BADGE,
            true
        )

        // The bottom card row now shows recently used apps instead of the
        // search engine list; the pager stays hidden in the quick search UI.
        binding.enginePager.isVisible = false
        binding.recentAppsRow.apply {
            layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
            adapter = recentAppsAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun saveSearchEngineOrder(orderedEngines: List<SearchEngine>) {
        searchEngines.clear()
        searchEngines.addAll(orderedEngines)
        lifecycleScope.launch(ioDispatcher) {
            val hideList = SearchEngineDataManager.readSearchEngineList(activity.applicationContext)
                .filter { !it.showInSearchEngineList }
            SearchEngineDataManager.saveSearchEngineList(
                activity.applicationContext,
                orderedEngines + hideList
            )
        }
    }

    private fun updateSearchEngineIcon() {
        currentSearchEngine?.let { engine ->
            loadEngineIcon(binding.engineIcon, engine, showImageBadge)
        }
    }

    private fun loadEngineIcon(view: RoundImageTextView, engine: SearchEngine, badge: Boolean) {
        val token = Any()
        iconBindings[view] = token
        view.setBadge(null)
        view.setText(if (engine.iconType == IconType.TEXT) engine.textIcon.orEmpty() else engine.name.orEmpty())
        if (engine.engineType == EngineType.SHARE_IMAGE_TO_APP && badge) {
            view.setBadge(R.drawable.baseline_image_24)
        }
        if (engine.iconType == IconType.TEXT) return
        lifecycleScope.launch(uiDispatcher) {
            val icon: Drawable? = try {
                withContext(ioDispatcher) {
                    val bitmap = if (engine.iconType == IconType.URI) {
                        engine.icon?.let { IconHelper.getIconBitmap(it) }
                    } else null
                    bitmap?.let { it.toDrawable(activity.resources) }
                        ?: engine.targetPackage?.takeIf { it.isNotEmpty() }
                            ?.let { IconHelper.getIcon(activity.applicationContext, it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SearchEngineManager", "Unable to load engine icon", e)
                null // Keep the text fallback when an icon file/package becomes unavailable.
            }
            // Pager children are reused when engines are reordered.
            if (icon != null && iconBindings[view] === token) view.setImage(icon)
        }
    }

    private suspend fun updateSearchEngineCardVisibility() {
        binding.engineIcon.isVisible = searchEngines.isNotEmpty()
        val hasRecentApps = recentApps.isNotEmpty()
        val card = binding.bottomEngineCard
        val initialHeight = card.height
        if (!hasRecentApps && bottomInputLayoutEnabled && card.isVisible && initialHeight > 0) {
            // An empty list is only known after IO. Collapse its reserved space
            // smoothly rather than snapping the whole combined card to a new height.
            val params = card.layoutParams
            val originalHeight = params.height
            val interpolator = FastOutSlowInInterpolator()
            val startedAt = SystemClock.uptimeMillis()
            try {
                do {
                    val progress = ((SystemClock.uptimeMillis() - startedAt) / 180f).coerceIn(0f, 1f)
                    params.height = (initialHeight * (1f - interpolator.getInterpolation(progress))).toInt()
                    card.layoutParams = params
                    if (progress >= 1f) break
                    delay(16L)
                } while (true)
                card.isVisible = false
            } finally {
                params.height = originalHeight
                card.layoutParams = params
            }
        } else {
            card.isVisible = hasRecentApps
        }
    }

    fun hasRecentApps(): Boolean = recentApps.isNotEmpty()

    private fun loadRecentApps() {
        lifecycleScope.launch(uiDispatcher) {
            val recents = withContext(ioDispatcher) {
                try {
                    RecentAppsRecorder.getRecentApps(RECENT_APPS_LIMIT)
                        .filter { it.lastUsageTime > 0 && !it.isRemove }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("SearchEngineManager", "Unable to load recent apps", e)
                    emptyList()
                }
            }
            recentApps = recents
            recentAppsAdapter.submit(recents)
            updateSearchEngineCardVisibility()
        }
    }

    private fun openRecentApp(app: APPModel) {
        lifecycleScope.launch(uiDispatcher) {
            val link = uriBuilder(GestureActionType.OPEN_APP)
                .appendQueryParameter("pkg", app.pkgName)
                .appendQueryParameter("freeform", false.toString())
                .apply {
                    activity.triggerSide?.let { appendQueryParameter("triggerSide", it) }
                }
                .build()
            ActionProxy(activity, link.toString()).execute(side = activity.triggerSide)
            activity.finishWithAnimation()
        }
    }

    private inner class RecentAppsAdapter : RecyclerView.Adapter<RecentAppsAdapter.Holder>() {

        private var items: List<APPModel> = emptyList()

        fun submit(list: List<APPModel>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val itemBinding = RecentAppItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(itemBinding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        inner class Holder(
            private val itemBinding: RecentAppItemBinding
        ) : RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(app: APPModel) {
                itemBinding.recentAppLabel.text = app.name
                val iconView = itemBinding.recentAppIcon
                iconView.applyGlobalImageRoundPercent = true
                iconView.setText(app.name)
                val token = Any()
                iconBindings[iconView] = token
                lifecycleScope.launch(uiDispatcher) {
                    val icon = try {
                        withContext(ioDispatcher) {
                            IconHelper.getIcon(activity.applicationContext, app.pkgName)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (icon != null && iconBindings[iconView] === token) {
                        iconView.setImage(icon)
                        iconView.clearTint()
                    }
                }
                itemBinding.root.setOnClickListener { openRecentApp(app) }
            }
        }
    }

    private fun clickSearchEngine(engine: SearchEngine, isPress: Boolean) {
        executeSearch(
            engine = engine,
            rawQuery = binding.searchInput.text?.toString().orEmpty(),
            freeform = isPress
        )
    }

    fun performSearchByCurrentEngine(query: String, freeform: Boolean = false): Boolean {
        if (!activity.canExecuteSearch) return false
        if (!engineDataLoaded) {
            pendingSearch = query to freeform
            return true
        }
        val engine = currentSearchEngine ?: return false
        executeSearch(
            engine = engine,
            rawQuery = query,
            freeform = freeform
        )
        return true
    }

    fun cancelPendingSearch() {
        pendingSearch = null
    }

    private fun executeSearch(engine: SearchEngine, rawQuery: String, freeform: Boolean) {
        val attachedImages = activity.getAttachedImageUris()

        when {
            engine.isImageSearchEngine() && attachedImages.isEmpty() -> {
                val clipboardImage = ClipboardUtils.getImage(activity.applicationContext)
                if (clipboardImage != null) {
                    lifecycleScope.launch(uiDispatcher) {
                        activity.attachClipboardImage(clipboardImage).join()
                        val updatedImages = activity.getAttachedImageUris()
                        executeSearchEngine(
                            engine = engine,
                            query = rawQuery,
                            imageUris = updatedImages.ifEmpty { listOf(clipboardImage) },
                            freeform = freeform
                        )
                    }
                } else {
                    requestImageAndSearch(engine, freeform)
                }
            }

            engine.isImageSearchEngine() -> {
                executeSearchEngine(
                    engine = engine,
                    query = rawQuery,
                    imageUris = attachedImages,
                    freeform = freeform
                )
            }

            rawQuery.isBlank() -> {
                val clipboardText = getClipboardTextOrShowMissing() ?: return
                executeSearchEngine(
                    engine = engine,
                    query = clipboardText,
                    imageUris = emptyList(),
                    freeform = freeform
                )
            }

            else -> {
                executeSearchEngine(
                    engine = engine,
                    query = rawQuery,
                    imageUris = emptyList(),
                    freeform = freeform
                )
            }
        }
    }

    private fun requestImageAndSearch(engine: SearchEngine, freeform: Boolean) {
        if (freeform) {
            activity.captureImageAndSearch(engine, freeform = true)
        } else {
            activity.pickImageAndSearch(engine, freeform = false)
        }
    }

    private fun executeSearchEngine(
        engine: SearchEngine,
        query: String,
        imageUris: List<Uri>,
        freeform: Boolean
    ) {
        SearchEngineExecutor(activity, engine, freeform)
        { activity.finishWithAnimation() }.execute(
            query = query,
            imageUris = imageUris
        )
    }

    private fun getClipboardTextOrShowMissing(): String? {
        val clipboardText = ClipboardUtils.getText(activity.applicationContext)
        if (clipboardText.isBlank()) {
            ToastHelper.showInUIThread(
                activity.applicationContext,
                R.string.clipboard_content_missing
            )
            return null
        }
        return clipboardText
    }

    private fun SearchEngine.isImageSearchEngine(): Boolean =
        engineType == EngineType.SHARE_IMAGE_TO_APP

    fun getCurrentSearchEngine(): SearchEngine? = currentSearchEngine

    fun getSearchEngines(): List<SearchEngine> = searchEngines.toList()

    private companion object {
        const val RECENT_APPS_LIMIT = 12
    }
}

fun setEngineIcon(
    context: Context,
    view: RoundImageTextView,
    item: SearchEngine,
    showImageBadge: Boolean = false
) {
    var success = false
    view.setBadge(null)

    when (item.iconType) {
        IconType.URI -> {
            val icon = item.icon?.let { IconHelper.getIconBitmap(it) }
            success = icon != null
            if (success) {
                view.setImage(icon)
            }
        }

        IconType.TEXT -> {
            success = true
            view.setText(item.textIcon ?: "")
        }

        else -> success = false
    }

    if (!success) {
        val targetPackage = item.targetPackage
        if (!targetPackage.isNullOrEmpty()) {
            view.setImage(IconHelper.getIcon(context, targetPackage))
        } else {
            view.setText(item.name ?: "")
        }
    }

    if (item.engineType == EngineType.SHARE_IMAGE_TO_APP && showImageBadge) {
        view.setBadge(R.drawable.baseline_image_24)
    }
}
