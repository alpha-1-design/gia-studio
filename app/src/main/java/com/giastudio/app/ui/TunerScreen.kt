package com.giastudio.app.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.giastudio.app.StudioController
import com.giastudio.app.ui.theme.Neo
import java.util.Locale

@Composable
fun TunerScreen(ctrl: StudioController, micGuard: (() -> Unit) -> Unit) {
    Column(Modifier.fillMaxSize().background(Neo.Paper)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeoLabel("VOCAL / INSTRUMENT TUNER", color = Neo.Ink)
            Spacer(Modifier.width(12.dp))
            Text(
                "Sing or play one steady note. GIA hears the pitch and shows how close you are — cents are 100ths of a semitone.",
                fontSize = 10.sp,
                color = Neo.InkSoft,
                modifier = Modifier.weight(1f),
            )
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.72f)
                        .background(Neo.Ink)
                        .border(4.dp, Neo.Ink)
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (ctrl.tuning) ctrl.tunerNote else "TAP START",
                            color = if (ctrl.tuning) Neo.Paper else Neo.Paper.copy(alpha = 0.5f),
                            fontSize = 76.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                        )
                        if (ctrl.tuning && ctrl.tunerFreq > 0.0) {
                            Text(
                                String.format(Locale.US, "%.1f Hz", ctrl.tunerFreq),
                                color = Neo.Paper.copy(alpha = 0.7f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                CentsMeter(cents = ctrl.tunerCents, active = ctrl.tuning && ctrl.tunerFreq > 0.0)

                Text(
                    when {
                        !ctrl.tuning -> "Press START and sing a clear, steady note"
                        ctrl.tunerLevel < 0.01f -> "Listening… make some sound"
                        ctrl.tunerFreq <= 0.0 -> "Sound detected — hold the note steady"
                        kotlin.math.abs(ctrl.tunerCents) <= 5 -> "IN TUNE — nice and steady"
                        else -> "Tune up or down to reach ${ctrl.tunerNote}"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    color = Neo.Ink,
                    textAlign = TextAlign.Center,
                )

                NeoMeter(ctrl.tunerLevel * 2f, Modifier.fillMaxWidth(0.5f).height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (ctrl.tuning) {
                        NeoButton("STOP", onClick = { ctrl.stopTuner() }, container = Neo.Record, fontSize = 15.sp)
                    } else {
                        NeoButton(
                            "START TUNING",
                            onClick = { micGuard { ctrl.startTuner() } },
                            container = Neo.Ink,
                            contentColor = Neo.Paper,
                            fontSize = 15.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.width(20.dp))
            Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Explain(
                    "A tuner measures the frequency of what you sing/play and names the nearest musical note. " +
                        "If it says C♯ −30¢ you are 30 hundredths of a semitone flat — sing slightly higher.",
                )
                TipCard(
                    "Warm up",
                    "Sing “ah” or “ee” on one comfortable note. Steady tone = stable reading.",
                )
                TipCard(
                    "What to do with it",
                    "Use the tuner before recording a take so your vocal lands in tune, then let the MIXER effects polish the sound.",
                )
                TipCard(
                    "Why the needle moves",
                    "Tiny shakes are normal. Aim for the middle and hold; ±5 cents is studio-tuned.",
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CentsMeter(cents: Int, active: Boolean) {
    Box(Modifier.fillMaxWidth(0.6f).height(46.dp).background(Neo.PaperRaised).border(3.dp, Neo.Ink)) {
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val w = size.width
            val h = size.height
            // target zone ±5 cents
            drawRect(
                color = Neo.Ok.copy(alpha = 0.35f),
                topLeft = Offset(w * 0.45f, 0f),
                size = androidx.compose.ui.geometry.Size(w * 0.1f, h),
            )
            // tick marks every 10 cents
            for (c in -50..50 step 10) {
                val x = w * (c + 50) / 100f
                val tall = c % 50 == 0
                drawLine(
                    color = Neo.Ink.copy(alpha = 0.55f),
                    start = Offset(x, if (tall) h * 0.25f else h * 0.5f),
                    end = Offset(x, h),
                    strokeWidth = if (tall) 2f else 1f,
                )
            }
            if (active) {
                val x = w * (cents.coerceIn(-50, 50) + 50) / 100f
                drawLine(
                    color = if (kotlin.math.abs(cents) <= 5) Neo.Ok else Neo.Record,
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 5f,
                )
            }
        }
    }
}

@Composable
private fun TipCard(title: String, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Neo.PaperRaised)
            .border(2.dp, Neo.Ink)
            .padding(8.dp),
    ) {
        Text(title.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.AccentHot)
        Text(body, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Neo.Ink)
    }
}
