package com.nyqova.app.ui

import android.app.Activity
import android.app.AlertDialog
import com.nyqova.app.R
import com.nyqova.app.settings.TvPreferences
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.Button
import android.widget.TextView

object TvStyle {
    data class Palette(val id: String,val title: String,val description: String,val nativeName: String,
        val background: Int,val top: Int,val panel: Int,val raised: Int,val text: Int,val muted: Int,val defaultAccent: String,val highContrast: Boolean=false)
    private fun color(value: String)=Color.parseColor(value)
    val palettes=listOf(
        Palette("classic","经典深色","柔和灰紫","Classic",color("#14121B"),color("#2B2738"),color("#272531"),color("#3B3847"),color("#F7F4FC"),color("#B3ADBF"),"#E6435A"),
        Palette("midnight","午夜蓝","冷静深蓝","Midnight",color("#07111E"),color("#132B46"),color("#14243A"),color("#233E5B"),color("#EDF5FF"),color("#AEC4DC"),"#72B7FF"),
        Palette("warm","暖调唱片","暖炭灰与米白","Warm",color("#1A1511"),color("#352A20"),color("#30271F"),color("#493A2C"),color("#F5E9D5"),color("#C8BBA5"),"#E5AB55"),
        Palette("contrast","清晰高对比","纯黑、清晰边界","Contrast",color("#000000"),color("#000000"),color("#101010"),color("#262626"),color("#FFFFFF"),color("#DDDDDD"),"#40E0D0",true)
    )
    val accents=linkedMapOf("#E6435A" to "Coral","#D6637C" to "Rose","#AF46DB" to "Purple","#4078ED" to "Blue","#7DA743" to "Green","#72B7FF" to "Ice","#E5AB55" to "Amber","#40E0D0" to "Cyan")
    var palette=palettes.first(); private set
    val background get()=palette.background
    val top get()=palette.top
    val panel get()=palette.panel
    val raised get()=palette.raised
    val text get()=palette.text
    val muted get()=palette.muted
    var accent = color("#E6435A")
    fun applyTheme(activity: Activity) {
        val prefs=TvPreferences(activity)
        palette=palettes.firstOrNull { it.id==prefs.text("appearance_theme","classic") } ?: palettes.first()
        val chosen=prefs.text("accent",palette.defaultAccent).takeIf { it in accents } ?: palette.defaultAccent
        accent=color(chosen)
        val styles=when(palette.id) {
            "midnight" -> intArrayOf(R.style.ThemeMidnight_Coral,R.style.ThemeMidnight_Rose,R.style.ThemeMidnight_Purple,R.style.ThemeMidnight_Blue,R.style.ThemeMidnight_Green,R.style.ThemeMidnight_Ice,R.style.ThemeMidnight_Amber,R.style.ThemeMidnight_Cyan)
            "warm" -> intArrayOf(R.style.ThemeWarm_Coral,R.style.ThemeWarm_Rose,R.style.ThemeWarm_Purple,R.style.ThemeWarm_Blue,R.style.ThemeWarm_Green,R.style.ThemeWarm_Ice,R.style.ThemeWarm_Amber,R.style.ThemeWarm_Cyan)
            "contrast" -> intArrayOf(R.style.ThemeContrast_Coral,R.style.ThemeContrast_Rose,R.style.ThemeContrast_Purple,R.style.ThemeContrast_Blue,R.style.ThemeContrast_Green,R.style.ThemeContrast_Ice,R.style.ThemeContrast_Amber,R.style.ThemeContrast_Cyan)
            else -> intArrayOf(R.style.ThemeClassic_Coral,R.style.ThemeClassic_Rose,R.style.ThemeClassic_Purple,R.style.ThemeClassic_Blue,R.style.ThemeClassic_Green,R.style.ThemeClassic_Ice,R.style.ThemeClassic_Amber,R.style.ThemeClassic_Cyan)
        }
        activity.setTheme(styles[accents.keys.indexOf(chosen)])
    }
    fun mix(base: Int,tint: Int,amount: Float): Int {
        fun channel(a: Int,b: Int)=(a+(b-a)*amount).toInt()
        return Color.rgb(channel(Color.red(base),Color.red(tint)),channel(Color.green(base),Color.green(tint)),channel(Color.blue(base),Color.blue(tint)))
    }
    fun accentSurface(amount: Float)=mix(panel,accent,amount)
    fun tileColors(kind: String?): IntArray {
        val strength=when(kind) { "favorites" -> .42f; "shuffle" -> .28f; "recent" -> .34f; else -> .18f }
        return intArrayOf(accentSurface(strength),mix(background,panel,.75f))
    }
    fun artworkBackground(coverColor: Int)=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(mix(background,coverColor,if(palette.highContrast) .12f else .5f),background))
    fun dp(context: Context, value: Int) = (context.resources.displayMetrics.density * value).toInt()
    fun shape(context: Context, color: Int, radius: Int = 10, focused: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
        if(focused) setStroke(dp(context,if(palette.highContrast) 3 else 2),accent)
        else if(palette.highContrast && Color.alpha(color)>0) setStroke(dp(context,1),0xff626262.toInt())
    }
    fun selector(context: Context, clear: Boolean = false) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), shape(context, accentSurface(.34f), focused = true))
        addState(intArrayOf(android.R.attr.state_focused), shape(context, accentSurface(.22f), focused = true))
        addState(intArrayOf(android.R.attr.state_selected), shape(context, accentSurface(.18f)))
        addState(intArrayOf(), shape(context, if(clear) Color.TRANSPARENT else panel))
    }
    fun dialog(context: Context): AlertDialog.Builder=object : AlertDialog.Builder(context) {
        override fun create(): AlertDialog=super.create().apply {
            setOnShowListener {
                window?.setBackgroundDrawable(shape(context,panel,12))
                listView?.selector=selector(context,true)
                listOf(AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL).forEach { which ->
                    getButton(which)?.background=selector(context,true)
                }
            }
        }
    }
    fun label(context: Context, value: String = "", size: Int = 14, color: Int = text, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); if(bold) setTypeface(typeface, Typeface.BOLD)
    }
    fun button(context: Context, value: String, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 14f; isAllCaps = false; setTextColor(this@TvStyle.text); background = selector(context,true)
        minHeight=dp(context,40); minWidth=dp(context,44)
        minimumHeight = dp(context, 40); minimumWidth = dp(context, 44)
        setPadding(dp(context, 12), 0, dp(context, 12), 0)
        setOnClickListener { action() }; id = View.generateViewId()
    }
    fun changed(view: TextView, value: CharSequence?) { if(view.text.toString() != value?.toString()) view.text = value }
}
