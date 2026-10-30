package io.github.lumiagg.gestureevo.settings.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.widget.ImageView
import androidx.core.content.withStyledAttributes
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import io.github.lumiagg.gestureevo.R

/**
 * Settings-home entry rendered the MIUIX way: the entry glyph is tinted with
 * its saturated color ([miuixGlyphColor]). Since round 2 the glyph stands on
 * its own — no pastel tile background is applied ([miuixTileColor] is still
 * parsed for layout compatibility but intentionally unused). Colors come
 * from the miuix token palette, so they follow light/night resources
 * automatically. Purely visual: all preference behavior stays with the
 * base class.
 */
class HomeEntryPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle,
    defStyleRes: Int = 0,
) : Preference(context, attrs, defStyleAttr, defStyleRes) {

    private var tileColor: Int = 0
    private var glyphColor: Int = 0

    init {
        context.withStyledAttributes(attrs, R.styleable.HomeEntryPreference) {
            tileColor = getColor(R.styleable.HomeEntryPreference_miuixTileColor, 0)
            glyphColor = getColor(R.styleable.HomeEntryPreference_miuixGlyphColor, 0)
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val iconView = holder.findViewById(android.R.id.icon) as? ImageView ?: return
        // Round 2: no icon tile background — clear any recycled tint and keep
        // only the per-entry glyph color.
        iconView.background = null
        iconView.backgroundTintList = null
        if (glyphColor != 0) {
            iconView.imageTintList = ColorStateList.valueOf(glyphColor)
        }
    }
}
