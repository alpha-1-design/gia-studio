package com.giastudio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.giastudio.app.BuildConfig
import com.giastudio.app.StudioController
import com.giastudio.app.audio.NativeCore
import com.giastudio.app.ui.theme.Neo
import com.giastudio.app.update.UpdateInfo
import com.giastudio.app.update.UpdateManager
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(ctrl: StudioController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    NativeCore.ensureLoaded()

    var update by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var progress by remember { mutableStateOf(0f) }
    var engineOn by remember { mutableStateOf(NativeCore.active()) }

    // If the user navigates away, never leave the native engine running.
    DisposableEffect(Unit) {
        onDispose {
            NativeCore.stop()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Neo.Paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeoLabel("SETTINGS", color = Neo.Ink)
            Spacer(Modifier.weight(1f))
            Text(
                "GIA Studio ${BuildConfig.VERSION_NAME} — GPLv3",
                fontSize = 9.sp,
                color = Neo.InkSoft,
                fontWeight = FontWeight.Bold,
            )
        }

        SettingsCard("UPDATES (over the air)") {
            Text(
                "GIA checks this project's GitHub Releases and installs newer builds straight onto your phone — no Play Store, no cable.",
                fontSize = 11.sp,
                color = Neo.InkSoft,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Installed: v${BuildConfig.VERSION_NAME}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                color = Neo.Ink,
            )
            Spacer(Modifier.height(8.dp))

            when (val u = update) {
                is UpdateState.Idle -> NeoButton(
                    "CHECK FOR UPDATES",
                    onClick = {
                        update = UpdateState.Checking
                        scope.launch {
                            val info = UpdateManager.check()
                            update = if (info == null) UpdateState.UpToDate else UpdateState.Found(info)
                        }
                    },
                )

                is UpdateState.Checking -> NeoButton(
                    "CHECKING…",
                    onClick = {},
                    enabled = false,
                )

                is UpdateState.UpToDate -> {
                    Text(
                        "You're on the latest version. 👍",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        color = Neo.Ok,
                    )
                    Spacer(Modifier.height(6.dp))
                    NeoButton(
                        "CHECK AGAIN",
                        onClick = {
                            update = UpdateState.Checking
                            scope.launch {
                                val info = UpdateManager.check()
                                update = if (info == null) UpdateState.UpToDate else UpdateState.Found(info)
                            }
                        },
                        container = Neo.PaperRaised,
                        contentColor = Neo.Ink,
                    )
                }

                is UpdateState.Found -> {
                    Text(
                        "v${u.info.version} is available",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        color = Neo.Ink,
                    )
                    if (u.info.notes.isNotBlank()) {
                        Text(u.info.notes, fontSize = 11.sp, color = Neo.InkSoft, maxLines = 3)
                    }
                    Spacer(Modifier.height(6.dp))
                    NeoButton(
                        "DOWNLOAD UPDATE",
                        onClick = {
                            update = UpdateState.Downloading(u.info)
                            progress = 0f
                            scope.launch {
                                val file = UpdateManager.download(context, u.info) { done, total ->
                                    progress = if (total > 0) done.toFloat() / total.toFloat() else 0f
                                }
                                update = if (file != null) {
                                    UpdateState.Ready(u.info, file)
                                } else {
                                    UpdateState.Error("Download failed — check your connection and try again.")
                                }
                            }
                        },
                    )
                }

                is UpdateState.Downloading -> {
                    Text(
                        "Downloading v${u.info.version}…",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        color = Neo.Ink,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .border(2.dp, Neo.Ink)
                            .background(Neo.Paper),
                    ) {
                        Box(
                            Modifier
                                .weight(progress.coerceIn(0.02f, 1f))
                                .fillMaxSize()
                                .background(Neo.Accent),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${(progress * 100).toInt()}%",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = Neo.InkSoft,
                    )
                }

                is UpdateState.Ready -> {
                    Text(
                        "v${u.info.version} downloaded — ready to install.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        color = Neo.Ok,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NeoButton(
                            "INSTALL NOW",
                            onClick = {
                                val err = UpdateManager.install(context, u.file)
                                if (err != null) {
                                    ctrl.toast("Couldn't open the installer: $err")
                                } else {
                                    ctrl.toast("Installer opened — follow the on-screen prompts. Allow 'install unknown apps' if asked.")
                                }
                            },
                        )
                        NeoButton(
                            "RE-CHECK",
                            onClick = {
                                update = UpdateState.Checking
                                scope.launch {
                                    val info = UpdateManager.check()
                                    update = if (info == null) UpdateState.UpToDate else UpdateState.Found(info)
                                }
                            },
                            container = Neo.PaperRaised,
                            contentColor = Neo.Ink,
                        )
                    }
                }

                is UpdateState.Error -> {
                    Text(u.msg, fontSize = 11.sp, color = Neo.RecordDark, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(6.dp))
                    NeoButton(
                        "TRY AGAIN",
                        onClick = {
                            update = UpdateState.Checking
                            scope.launch {
                                val info = UpdateManager.check()
                                update = if (info == null) UpdateState.UpToDate else UpdateState.Found(info)
                            }
                        },
                        container = Neo.PaperRaised,
                        contentColor = Neo.Ink,
                    )
                }
            }
        }

        SettingsCard("AUDIO CORE (C++ engine)") {
            val native = NativeCore.available
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(12.dp)
                        .height(12.dp)
                        .background(if (native) Neo.Ok else Neo.Disabled)
                        .border(2.dp, Neo.Ink),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (native) "Native core present" else "Not in this build",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    color = Neo.Ink,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (native) {
                    "The C++ Oboe engine is bundled. It hosts plugins in a real-time render loop — the foundation for third-party instruments. Experimental."
                } else {
                    "This APK was built without the C++ core — the Kotlin engine runs everything and works great. Builds from GitHub include the core."
                },
                fontSize = 11.sp,
                color = Neo.InkSoft,
            )
            if (native) {
                Spacer(Modifier.height(8.dp))
                Row(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeoButton(
                        if (engineOn) "STOP ENGINE" else "START ENGINE",
                        onClick = {
                            if (engineOn) {
                                NativeCore.stop()
                                engineOn = false
                            } else {
                                val ok = NativeCore.start(48_000)
                                engineOn = NativeCore.active()
                                if (!ok) ctrl.toast("The native engine could not start on this device.")
                            }
                        },
                        container = if (engineOn) Neo.Record else Neo.Ink,
                    )
                    NeoButton(
                        "PLAY TEST NOTE",
                        onClick = {
                            if (!engineOn) {
                                val ok = NativeCore.start(48_000)
                                engineOn = NativeCore.active()
                                if (!ok) {
                                    ctrl.toast("Start the engine first.")
                                    return@NeoButton
                                }
                            }
                            scope.launch {
                                val riff = intArrayOf(60, 64, 67, 72)
                                for (note in riff) {
                                    NativeCore.playNote(note, 110)
                                    delay(200)
                                    NativeCore.noteOff(note)
                                }
                            }
                        },
                        container = Neo.PaperRaised,
                        contentColor = Neo.Ink,
                    )
                }
                Spacer(Modifier.height(8.dp))
                val count = NativeCore.pluginCount()
                if (count > 0) {
                    Text("Plugins hosted: ${(0 until count).joinToString { NativeCore.pluginName(it) }}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Neo.InkSoft)
                }
                Spacer(Modifier.height(2.dp))
                Explain(
                    "The C++ core uses Oboe (Google's low-latency audio library). This experimental slice proves the plugin pipeline: future updates will route the Create tab's instruments through it and add installable plugins."
                )
            }
        }

        SettingsCard("ABOUT") {
            Text("GIA Studio — an open-source pocket studio for Android.", fontSize = 11.sp, color = Neo.InkSoft)
            Text("Code: github.com/alpha-1-design/gia-studio", fontSize = 11.sp, color = Neo.InkSoft)
            Text("License: GNU GPL v3", fontSize = 11.sp, color = Neo.InkSoft)
            Text("Updates are delivered from the project's GitHub Releases.", fontSize = 11.sp, color = Neo.InkSoft)
        }
    }
}

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Found(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Error(val msg: String) : UpdateState
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Neo.PaperRaised)
            .border(2.dp, Neo.Ink)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(14.dp).height(14.dp).background(Neo.Accent).border(2.dp, Neo.Ink))
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
        }
        Spacer(Modifier.height(2.dp))
        content()
    }
}