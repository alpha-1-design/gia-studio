package com.giastudio.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.giastudio.app.StudioController
import com.giastudio.app.ui.theme.Neo
import kotlinx.coroutines.delay

private enum class Tab(val label: String, val icon: ImageVector) {
    STUDIO("Studio", Icons.Filled.GraphicEq),
    MIXER("Mixer", Icons.Filled.Tune),
    TUNER("Tuner", Icons.Filled.MusicNote),
    HELP("Help", Icons.Filled.HelpOutline),
}

@Composable
fun GiaApp(ctrl: StudioController) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(Tab.STUDIO) }
    var dialog by remember { mutableStateOf<AppDialog?>(null) }
    var pendingMicAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var importTrack by remember { mutableStateOf(0) }

    val prefs = remember { context.getSharedPreferences("gia", Context.MODE_PRIVATE) }
    var showWelcome by remember { mutableStateOf(!prefs.getBoolean("seen_intro", false)) }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingMicAction
        pendingMicAction = null
        if (granted) action?.invoke() else ctrl.toast("Microphone permission is off — enable it in your phone's app settings to record or tune.")
    }

    fun micGuard(action: () -> Unit) {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (ok) action() else {
            pendingMicAction = action
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && importTrack != 0) ctrl.importWav(importTrack, uri)
    }

    LaunchedEffect(tab) {
        if (tab != Tab.TUNER) ctrl.stopTuner()
    }

    // auto-dismiss toasts
    LaunchedEffect(ctrl.toastMsg) {
        if (ctrl.toastMsg != null) {
            delay(4200)
            ctrl.consumeToast()
        }
    }

    Box(Modifier.fillMaxSize().background(Neo.Paper)) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).fillMaxSize()) {
            TopBar(ctrl, onAction = { d -> dialog = d })
            Row(Modifier.weight(1f).fillMaxWidth()) {
                NavRail(tab, onSelect = { tab = it })
                when (tab) {
                    Tab.STUDIO -> StudioScreen(ctrl, micGuard = ::micGuard)
                    Tab.MIXER -> MixerScreen(ctrl)
                    Tab.TUNER -> TunerScreen(ctrl, micGuard = ::micGuard)
                    Tab.HELP -> HelpScreen(ctrl)
                }
            }
        }

        if (ctrl.busy) BusyOverlay(ctrl)
        ctrl.toastMsg?.let { msg ->
            ToastCard(msg, Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp))
        }

        when (dialog) {
            AppDialog.NEW -> SessionNameDialog(
                title = "New session",
                initial = "",
                confirmLabel = "CREATE",
                onConfirm = { ctrl.newSession(it) },
                onDismiss = { dialog = null },
            )
            AppDialog.RENAME -> SessionNameDialog(
                title = "Rename session",
                initial = ctrl.project.name,
                confirmLabel = "RENAME",
                onConfirm = { ctrl.renameSession(it) },
                onDismiss = { dialog = null },
            )
            AppDialog.OPEN -> OpenDialog(ctrl) { dialog = null }
            AppDialog.EXPORT -> ExportDialog(ctrl) { dialog = null }
            AppDialog.IMPORT -> ImportDialog(
                ctrl,
                onPickTrack = { trackId ->
                    importTrack = trackId
                    dialog = null
                    importPicker.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*"))
                },
                onDismiss = { dialog = null },
            )
            null -> Unit
        }

        if (showWelcome) {
            WelcomeDialog(
                onDone = {
                    prefs.edit().putBoolean("seen_intro", true).apply()
                    showWelcome = false
                },
                onHelp = {
                    prefs.edit().putBoolean("seen_intro", true).apply()
                    showWelcome = false
                    tab = Tab.HELP
                },
            )
        }
    }
}

private enum class AppDialog { NEW, RENAME, OPEN, EXPORT, IMPORT }

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

@Composable
private fun TopBar(ctrl: StudioController, onAction: (AppDialog) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(Neo.Ink)
            .border(3.dp, Neo.Ink)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).background(Neo.Accent).border(2.dp, Neo.Ink), contentAlignment = Alignment.Center) {
            Text("G", fontSize = 17.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
        }
        Spacer(Modifier.width(8.dp))
        Text("GIA STUDIO", color = Neo.Paper, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier
                .background(Neo.PaperRaised.copy(alpha = 0.14f))
                .border(2.dp, Neo.Paper.copy(alpha = 0.5f))
                .clickable { onAction(AppDialog.RENAME) }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ctrl.project.name,
                    color = Neo.Paper,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                if (ctrl.dirty) {
                    Spacer(Modifier.width(4.dp))
                    Text("*", color = Neo.Accent, fontSize = 13.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TopIcon(Icons.Filled.Add, "New") { onAction(AppDialog.NEW) }
        TopIcon(Icons.Filled.FolderOpen, "Open") { onAction(AppDialog.OPEN) }
        TopIcon(Icons.Filled.Save, "Save") { ctrl.saveSession() }
        TopIcon(Icons.Filled.Upload, "Import audio") { onAction(AppDialog.IMPORT) }
        TopIcon(Icons.Filled.Share, "Export WAV") { onAction(AppDialog.EXPORT) }
    }
}

@Composable
private fun TopIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .padding(start = 2.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = Neo.Paper, modifier = Modifier.size(20.dp))
        Text(label.uppercase(), color = Neo.Paper.copy(alpha = 0.65f), fontSize = 6.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// Nav rail
// ---------------------------------------------------------------------------

@Composable
private fun NavRail(tab: Tab, onSelect: (Tab) -> Unit) {
    Column(
        Modifier
            .width(58.dp)
            .fillMaxHeight()
            .background(Neo.Ink),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Tab.entries.forEach { t ->
            val selected = t == tab
            Column(
                Modifier
                    .width(52.dp)
                    .background(if (selected) Neo.Accent else Neo.Ink.copy(alpha = 0f))
                    .border(2.dp, if (selected) Neo.Ink else Neo.Paper.copy(alpha = 0.55f))
                    .clickable { onSelect(t) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    t.icon,
                    contentDescription = t.label,
                    tint = if (selected) Neo.Ink else Neo.Paper,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    t.label,
                    color = if (selected) Neo.Ink else Neo.Paper.copy(alpha = 0.8f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text("v0.1", color = Neo.Paper.copy(alpha = 0.4f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
    }
}

// ---------------------------------------------------------------------------
// Overlays
// ---------------------------------------------------------------------------

@Composable
private fun BusyOverlay(ctrl: StudioController) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Neo.Ink.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(280.dp)
                .background(Neo.PaperRaised)
                .border(3.dp, Neo.Ink)
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(ctrl.busyText, fontSize = 13.sp, fontWeight = FontWeight.Black, color = Neo.Ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            if (ctrl.progress > 0f) {
                Row(Modifier.fillMaxWidth().height(12.dp).border(2.dp, Neo.Ink).background(Neo.Paper)) {
                    Box(
                        Modifier
                            .weight(ctrl.progress.coerceIn(0f, 1f).coerceAtLeast(0.01f))
                            .fillMaxHeight()
                            .background(Neo.Accent),
                    )
                }
            } else {
                Box(Modifier.fillMaxWidth().height(6.dp).background(Neo.Disabled))
            }
        }
    }
}

@Composable
private fun ToastCard(msg: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Neo.Ink)
            .border(3.dp, Neo.Accent)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(msg, color = Neo.Paper, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 3)
    }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

@Composable
private fun Scrim(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Neo.Ink.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .background(Neo.PaperRaised)
                .border(3.dp, Neo.Ink)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .padding(14.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun DialogTitle(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
}

@Composable
private fun NeoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .background(Neo.Paper)
            .border(3.dp, Neo.Ink)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Neo.Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            ),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, color = Neo.InkSoft, fontSize = 14.sp)
                }
                inner()
            },
        )
    }
}

@Composable
private fun SessionNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    Scrim(onDismiss) {
        DialogTitle(title)
        Spacer(Modifier.height(10.dp))
        NeoTextField(name, { name = it }, "Session name", Modifier.width(260.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeoButton("CANCEL", onClick = onDismiss, container = Neo.PaperRaised, contentColor = Neo.Ink)
            NeoButton(confirmLabel, onClick = { onConfirm(name) })
        }
    }
}

@Composable
private fun OpenDialog(ctrl: StudioController, onDismiss: () -> Unit) {
    val sessions = remember { ctrl.listProjects() }
    Scrim(onDismiss) {
        DialogTitle("Open a saved session")
        Spacer(Modifier.height(8.dp))
        if (sessions.isEmpty()) {
            Text("Nothing saved yet. Save this session from the top bar (floppy icon).", fontSize = 11.sp, color = Neo.InkSoft)
        } else {
            Column(Modifier.width(280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sessions.forEach { name ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Neo.Paper)
                            .border(2.dp, Neo.Ink)
                            .clickable {
                                ctrl.loadSession(name)
                                onDismiss()
                            }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        Text(name.removeSuffix(".gia.json"), fontSize = 12.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportDialog(ctrl: StudioController, onDismiss: () -> Unit) {
    var bits by remember { mutableStateOf(16) }
    var normalize by remember { mutableStateOf(false) }
    val hasAudio = ctrl.project.tracks.any { it.clips.isNotEmpty() }
    Scrim(onDismiss) {
        DialogTitle("Export mix as WAV")
        Spacer(Modifier.height(6.dp))
        Text(
            "GIA renders the whole arrangement through every fader and effect — exactly what you hear — into one stereo file you can share.",
            fontSize = 11.sp,
            color = Neo.InkSoft,
        )
        Spacer(Modifier.height(10.dp))
        if (!hasAudio) {
            Text("There's nothing to export yet — record or import a clip first.", fontSize = 12.sp, color = Neo.RecordDark, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BIT DEPTH", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.InkSoft)
            Spacer(Modifier.width(10.dp))
            NeoChip("16 (share)", active = bits == 16, onClick = { bits = 16 }, activeColor = Neo.Ink)
            Spacer(Modifier.width(6.dp))
            NeoChip("24 (master)", active = bits == 24, onClick = { bits = 24 }, activeColor = Neo.Ink)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeoChip("NORMALIZE", active = normalize, onClick = { normalize = !normalize }, activeColor = Neo.Ok)
            Spacer(Modifier.width(8.dp))
            Text("Automatically make the loudest moment hit a strong level.", fontSize = 10.sp, color = Neo.InkSoft)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeoButton("CANCEL", onClick = onDismiss, container = Neo.PaperRaised, contentColor = Neo.Ink)
            NeoButton(
                "RENDER & SHARE",
                onClick = {
                    onDismiss()
                    ctrl.exportAndShare(bits, normalize)
                },
                enabled = hasAudio,
            )
        }
    }
}

@Composable
private fun ImportDialog(
    ctrl: StudioController,
    onPickTrack: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Scrim(onDismiss) {
        DialogTitle("Import audio into a track")
        Spacer(Modifier.height(6.dp))
        Text("Choose the destination track (WAV files in this version).", fontSize = 11.sp, color = Neo.InkSoft)
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.width(300.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ctrl.project.tracks.forEach { t ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Neo.Paper)
                        .border(2.dp, Neo.Ink)
                        .clickable { onPickTrack(t.id) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).background(Neo.trackColor(t.color)).border(2.dp, Neo.Ink))
                    Spacer(Modifier.width(8.dp))
                    Text(t.name, fontSize = 12.sp, fontWeight = FontWeight.Black, color = Neo.Ink, modifier = Modifier.weight(1f))
                    Text("${t.clips.size} clips", fontSize = 9.sp, color = Neo.InkSoft)
                }
            }
        }
    }
}

@Composable
private fun WelcomeDialog(onDone: () -> Unit, onHelp: () -> Unit) {
    Scrim(onDismiss = onDone) {
        DialogTitle("Welcome to GIA Studio 🎛️")
        Spacer(Modifier.height(6.dp))
        Text("A pocket studio on your phone. Hold it sideways — everything below happens in 3 moves:", fontSize = 11.sp, color = Neo.InkSoft)
        Spacer(Modifier.height(8.dp))
        WelcomeStep("1", "Record", "Tap the red ● on a track (ARM), then press REC and sing or play.")
        WelcomeStep("2", "Clean up", "Tap your clip → Normalize / Denoise / Trim + fades to make the take sound polished.")
        WelcomeStep("3", "Mix & share", "MIXER shapes the sound (EQ, delay, reverb, faders). Tuner tunes you. Export makes the WAV.")
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeoButton("I'LL EXPLORE", onClick = onDone, container = Neo.PaperRaised, contentColor = Neo.Ink)
            Spacer(Modifier.width(8.dp))
            NeoButton("SHOW ME MORE", onClick = onHelp, container = Neo.Ink, contentColor = Neo.Paper)
        }
    }
}

@Composable
private fun WelcomeStep(num: String, title: String, body: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(20.dp).background(Neo.Accent).border(2.dp, Neo.Ink), contentAlignment = Alignment.Center) {
            Text(num, fontSize = 11.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(300.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            Text(body, fontSize = 11.sp, color = Neo.InkSoft)
        }
    }
}
