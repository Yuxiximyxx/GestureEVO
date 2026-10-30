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
 * Settings-home entry rendered the MIUIX way: the entry icon sits on a pastel
 * rounded tile ([miuixTileColor]) and the glyph is tinted with the matching
 * saturated color ([miuixGlyphColor]). Colors come from the miuix token
 * palette, so they follow light/night resources automatically.
 * Purely visual: all preference behavior stays with the base class.
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
        if (tileColor != 0) {
            iconView.backgroundTintList = ColorStateList.valueOf(tileColor)
        }
        if (glyphColor != 0) {
            iconView.imageTintList = ColorStateList.valueOf(glyphColor)
        }
    }
}
