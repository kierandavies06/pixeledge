package uk.me.kierandavies.pixeledge

import android.R
import android.content.Context
import android.content.res.Configuration

/** Material 3 colour roles pulled from the system's dynamic (wallpaper) palette. */
class Pal(private val c: Context, dark: Boolean? = null) {
    private val night = dark ?: (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    private fun k(d: Int, l: Int) = c.getColor(if (night) d else l)
    val bg = k(R.color.system_neutral1_900, R.color.system_neutral1_50)
    val card = k(R.color.system_neutral1_800, R.color.system_neutral1_0)
    val field = k(R.color.system_neutral1_700, R.color.system_neutral2_100)
    val text = k(R.color.system_neutral1_100, R.color.system_neutral1_900)
    val dim = k(R.color.system_neutral2_300, R.color.system_neutral2_700)
    val primary = k(R.color.system_accent1_200, R.color.system_accent1_600)
    val onPrimary = k(R.color.system_accent1_800, R.color.system_accent1_0)
    val tonal = k(R.color.system_accent2_700, R.color.system_accent2_100)
    val onTonal = k(R.color.system_accent2_100, R.color.system_accent2_900)
    val outline = k(R.color.system_neutral2_500, R.color.system_neutral2_400)
    val err = if (night) 0xFF5C2B2B.toInt() else 0xFFFFDAD6.toInt()
    val onErr = if (night) 0xFFFFDAD6.toInt() else 0xFF410002.toInt()
}
