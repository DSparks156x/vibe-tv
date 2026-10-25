package com.vibe.tvlauncher.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.LocalTextStyle
import com.vibe.tvlauncher.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val Bg = Color(0xFF101114)
val CardBg = Color(0xFF1E2025)
val TextPrimary = Color(0xFFE8EAED)
val TextSecondary = Color(0xFF9AA0A6)
val Accent = Color(0xFF8AB4F8)

val PadH = 58.dp

val GoogleSans = FontFamily(
    Font(R.font.google_sans_regular, FontWeight.Normal),
    Font(R.font.google_sans_medium, FontWeight.Medium),
    Font(R.font.google_sans_bold, FontWeight.Bold),
)

@Composable
fun LauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Bg,
            surface = CardBg,
            onSurface = TextPrimary,
            onBackground = TextPrimary,
        ),
    ) {
        CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = GoogleSans), content = content)
    }
}

fun Context.launch(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Log.w("VibeTV", "no activity for $intent", e)
        Toast.makeText(this, "App not installed", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Log.w("VibeTV", "cannot launch $intent", e)
    }
}

/** Opens a YouTube video in whichever YouTube app is installed (TizenTube, SmartTube, official). */
fun Context.openYouTube(videoId: String) {
    val uri = Uri.parse("https://www.youtube.com/watch?v=$videoId")
    val pkg = com.vibe.tvlauncher.data.AppRepo.youtubePackage(this)
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri).apply { pkg?.let { setPackage(it) } }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")))
    }
}
