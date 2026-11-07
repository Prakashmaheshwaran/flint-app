package com.flint.peakfocus.core.common.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flint.peakfocus.core.common.theme.FlintPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The v3 app tile: a real launcher icon on an 11–18dp rounded square, with the resting variant
 * the block screens use (dimmed art, ember inset ring, a small bone lock). When PackageManager
 * has no icon (uninstalled target, preview, test host) it falls back to a tonal initial — never
 * a blank square.
 */
@Composable
fun FlintAppTile(
    packageName: String,
    label: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    resting: Boolean = false,
    cornerRadius: Dp? = null,
    /** An icon the caller already decoded off the main thread; preferred over a lookup. */
    preloadedIcon: ImageBitmap? = null,
) {
    val context = LocalContext.current.applicationContext
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val cacheKey = "$packageName@$sizePx"
    // PackageManager is a binder call and rasterizing allocates: never do either during
    // composition. Show the initial until the background load (or the cache) answers.
    val icon by produceState(preloadedIcon ?: TileIconCache.get(cacheKey), preloadedIcon, cacheKey) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName).toTileBitmap(sizePx) }
                .getOrNull()
                ?.also { TileIconCache.put(cacheKey, it) }
        }
    }
    val radius = cornerRadius ?: (size * 0.3f)
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(if (resting) Color(0xFF232226) else MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val shown = icon
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = null,
                alpha = if (resting) 0.26f else 1f,
                modifier = Modifier.size(size).clip(shape),
            )
        } else {
            Text(
                text = initialOf(label, packageName),
                color = if (resting) FlintPalette.Bone.copy(alpha = 0.26f) else FlintPalette.SoftBone,
                fontSize = (size.value * 0.34f).sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
        if (resting) {
            Box(Modifier.size(size).border(1.5.dp, FlintPalette.Ember.copy(alpha = 0.5f), shape))
            Icon(
                imageVector = FlintGlyphs.lock,
                contentDescription = null,
                tint = FlintPalette.Bone,
                modifier = Modifier.size(size * 0.42f),
            )
        }
    }
}

internal fun initialOf(label: String?, packageName: String): String {
    val source = label?.takeIf { it.isNotBlank() } ?: packageName
    return source.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

/** Rasterizes a launcher icon at [sizePx] through a framework Canvas (adaptive icons included). */
private fun Drawable.toTileBitmap(sizePx: Int): ImageBitmap {
    val px = sizePx.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, px, px)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}

/** Process-wide cache of rasterized tiles, keyed by package and pixel size (~64 tiles). */
private object TileIconCache {
    private val cache = LruCache<String, ImageBitmap>(64)
    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, icon: ImageBitmap) { cache.put(key, icon) }
}
