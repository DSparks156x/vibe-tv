package com.vibe.tvlauncher.settings

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.vibe.tvlauncher.ui.ActionMenu
import com.vibe.tvlauncher.ui.Bg
import com.vibe.tvlauncher.ui.CardBg
import com.vibe.tvlauncher.ui.LauncherTheme
import com.vibe.tvlauncher.ui.MenuAction
import com.vibe.tvlauncher.ui.PadH
import com.vibe.tvlauncher.ui.PillButton
import com.vibe.tvlauncher.ui.TextSecondary
import com.vibe.tvlauncher.ui.TvTextField
import com.vibe.tvlauncher.ui.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Launcher settings, plus a QR code that opens the same settings on a phone or computer. */
class SettingsActivity : ComponentActivity() {
    private var server: SetupServer? = null
    /** Bumped when the phone page saves something, so the TV list reloads. */
    private val version = mutableIntStateOf(0)
    private val setupUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LauncherTheme { SettingsScreen(version.intValue, setupUrl.value) }
        }
    }

    override fun onStart() {
        super.onStart()
        server = SetupServer(applicationContext) { runOnUiThread { version.intValue++ } }.also {
            runCatching { it.start() }
            setupUrl.value = it.url
        }
    }

    override fun onStop() {
        server?.stop()
        server = null
        setupUrl.value = null
        super.onStop()
    }
}

@Composable
private fun SettingsScreen(version: Int, setupUrl: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fields by remember { mutableStateOf<List<Field>?>(null) }
    var localVersion by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    /** The text setting being typed into; the keyboard only opens for this one. */
    var editing by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Field?>(null) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(version, localVersion) {
        fields = withContext(Dispatchers.IO) { SettingsRegistry.fields(context) }
    }
    LaunchedEffect(fields != null) { if (fields != null) runCatching { firstFocus.requestFocus() } }

    fun save(key: String, value: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { SettingsRegistry.apply(context, mapOf(key to value)) }
            message = r[key]
            localVersion++
        }
    }

    picking?.let { f ->
        ActionMenu(
            f.label, null,
            f.choices.map { (id, label) -> MenuAction(if (id == f.value) "$label  ✓" else label) { save(f.key, id) } },
        ) { picking = null }
    }

    Row(Modifier.fillMaxSize().background(Bg).padding(start = PadH, end = PadH, top = 40.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", color = Color.White, fontSize = 30.sp)
                Spacer(Modifier.width(20.dp))
                PillButton(
                    "Android settings",
                    onClick = { context.launch(Intent(android.provider.Settings.ACTION_SETTINGS)) },
                    modifier = Modifier.focusRequester(firstFocus),
                )
            }
            message?.let { Text(it, color = Color(0xFFF28B82), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(12.dp))
            val list = fields?.visible()
            if (list == null) {
                Text("Loading…", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.clipToBounds(),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 60.dp, end = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    list.groupBy { it.section }.forEach { (section, group) ->
                        item(key = "h_$section") {
                            Text(
                                section, color = Color(0xFF8AB4F8), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                            )
                        }
                        items(group, key = { it.key }) { f ->
                            FieldRow(
                                f,
                                editing = editing == f.key,
                                onEdit = { editing = f.key },
                                onEditDone = { if (editing == f.key) editing = null },
                                onPick = { picking = f },
                                save = ::save,
                            )
                        }
                    }
                }
            }
        }
        SetupCard(setupUrl)
    }
}

@Composable
private fun FieldRow(
    f: Field,
    editing: Boolean,
    onEdit: () -> Unit,
    onEditDone: () -> Unit,
    onPick: () -> Unit,
    save: (String, String) -> Unit,
) {
    val rowFocus = remember { FocusRequester() }
    var wasEditing by remember { mutableStateOf(false) }
    // Put focus back on the row after typing, so the D-pad continues from here.
    LaunchedEffect(editing) {
        if (wasEditing && !editing) runCatching { rowFocus.requestFocus() }
        wasEditing = editing
    }
    Column(Modifier.fillMaxWidth()) {
        if (editing && (f.type == FieldType.Text || f.type == FieldType.Secret)) {
            EditBox(f, onEditDone, save)
        } else {
            val shown = when (f.type) {
                FieldType.Toggle -> if (f.value == "true") "On" else "Off"
                FieldType.Choice -> f.choices.firstOrNull { it.first == f.value }?.second ?: f.value
                FieldType.Secret -> if (f.value.isNotEmpty()) "Saved" else "Not set"
                FieldType.Text -> f.value.ifEmpty { f.emptyLabel }
            }
            ValueRow(f.label, shown, Modifier.focusRequester(rowFocus)) {
                when (f.type) {
                    FieldType.Toggle -> save(f.key, (f.value != "true").toString())
                    FieldType.Choice -> onPick()
                    else -> onEdit()
                }
            }
        }
        if (f.help.isNotEmpty()) Text(f.help, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Inline editor for a text or secret setting: focused (keyboard up) right away; Done saves, Back cancels. */
@Composable
private fun EditBox(f: Field, onDone: () -> Unit, save: (String, String) -> Unit) {
    val focus = remember { FocusRequester() }
    val secret = f.type == FieldType.Secret
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { onDone() }
    Text(f.label, color = Color.White, fontSize = 15.sp)
    Spacer(Modifier.height(6.dp))
    TvTextField(
        hint = if (secret) "Type or paste the key, then Done" else "Type, then Done",
        secret = secret,
        modifier = Modifier.focusRequester(focus),
        initial = if (secret) "" else f.value,
    ) {
        if (it != f.value) save(f.key, it)
        onDone()
    }
    if (secret && f.value.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        PillButton("Remove saved key") { save(f.key, SettingsRegistry.CLEAR); onDone() }
    }
}

@Composable
private fun ValueRow(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CardBg,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF202124),
        ),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SetupCard(url: String?) {
    val qr = remember(url) { url?.let { qrBitmap(it, 512) } }
    Column(
        Modifier.width(300.dp).clip(RoundedCornerShape(16.dp)).background(CardBg).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Set up from your phone or computer", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Scan the code, or open the address on any device on the same network, to paste keys and URLs.",
            color = TextSecondary, fontSize = 12.sp,
        )
        Spacer(Modifier.height(14.dp))
        if (qr != null && url != null) {
            Box(Modifier.size(200.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(10.dp)) {
                Image(qr, contentDescription = "Setup QR code", modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
            }
            Spacer(Modifier.height(12.dp))
            Text(url.substringBefore("/?"), color = Color.White, fontSize = 14.sp)
            Text("Code " + url.substringAfter("c="), color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text("Works only while this screen is open.", color = TextSecondary, fontSize = 11.sp)
        } else {
            Text("Not connected to a network.", color = TextSecondary, fontSize = 13.sp)
        }
    }
}

private fun qrBitmap(text: String, size: Int): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
