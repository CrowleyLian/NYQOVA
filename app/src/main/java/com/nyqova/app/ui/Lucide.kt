package com.nyqova.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.widget.Button
import android.widget.TextView
import com.nyqova.app.R

enum class Icon(val resource: Int) {
    HOME(R.drawable.ic_lucide_house), HEART(R.drawable.ic_lucide_heart), HEART_FILLED(R.drawable.ic_lucide_heart_filled),
    CLOCK(R.drawable.ic_lucide_clock), DISC(R.drawable.ic_lucide_disc), ARTIST(R.drawable.ic_lucide_mic_vocal),
    GENRE(R.drawable.ic_lucide_shapes), LIBRARY(R.drawable.ic_lucide_library), FOLDER(R.drawable.ic_lucide_folder),
    SETTINGS(R.drawable.ic_lucide_settings), QUEUE(R.drawable.ic_lucide_list_music), MUSIC(R.drawable.ic_lucide_eighth_notes),
    SEARCH(R.drawable.ic_lucide_search), MORE(R.drawable.ic_lucide_ellipsis), PREVIOUS(R.drawable.ic_lucide_skip_back),
    NEXT(R.drawable.ic_lucide_skip_forward), PLAY(R.drawable.ic_lucide_play), PAUSE(R.drawable.ic_lucide_pause),
    REPEAT(R.drawable.ic_lucide_repeat), REPEAT_ONE(R.drawable.ic_lucide_repeat_1), SHUFFLE(R.drawable.ic_lucide_shuffle),
    DOWN(R.drawable.ic_lucide_chevron_down), FULLSCREEN(R.drawable.ic_lucide_maximize), CLOSE(R.drawable.ic_lucide_x),
    TRASH(R.drawable.ic_lucide_trash), COLLAPSE(R.drawable.ic_lucide_panel_left_close), EXPAND(R.drawable.ic_lucide_panel_left_open),
    PLUS(R.drawable.ic_lucide_plus), COLOR(R.drawable.ic_lucide_circle_filled)
}

/** Bundled native vectors, independent of installed fonts, networking and CPU architecture. */
object Lucide {
    fun drawable(context: Context, icon: Icon, size: Int = 24, color: Int = TvStyle.text): Drawable =
        context.getDrawable(icon.resource)!!.mutate().apply { setTint(color); val pixels=TvStyle.dp(context,size); setBounds(0,0,pixels,pixels) }

    fun decorate(view: TextView, icon: Icon?, size: Int = 22, color: Int = TvStyle.text) {
        val iconOnly=view.text.isBlank()
        val identity="$icon:$size:$color:$iconOnly"
        view.compoundDrawablePadding=if(view.text.isBlank()) 0 else TvStyle.dp(view.context,9)
        if(view.getTag(R.id.lucide_icon)==identity) return
        view.setTag(R.id.lucide_icon,identity)
        if(iconOnly && icon!=null) {
            view.setCompoundDrawablesRelative(null,null,null,null)
            view.foreground=android.graphics.drawable.LayerDrawable(arrayOf(drawable(view.context,icon,size,color))).apply {
                val pixels=TvStyle.dp(view.context,size); setLayerSize(0,pixels,pixels)
            }
            view.foregroundGravity=android.view.Gravity.CENTER
        } else {
            view.foreground=null
            view.setCompoundDrawablesRelative(icon?.let { drawable(view.context,it,size,color) },null,null,null)
        }
    }

    fun button(context: Context, icon: Icon, label: String, iconOnly: Boolean = false, action: () -> Unit): Button =
        TvStyle.button(context,if(iconOnly) "" else label,action).apply {
            contentDescription=label; decorate(this,icon)
            compoundDrawableTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf(android.R.attr.state_selected),intArrayOf()),intArrayOf(TvStyle.accent,TvStyle.accent,TvStyle.text))
        }
}
