package com.jemcik.jemrec.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.jemcik.jemrec.clips.Clips

/**
 * THE CLIPS: sounds - a greeting, "please hold", a line to say again - played
 * into the phone call that is up. Play mutes the microphone and plays the clip
 * to the caller, with a counter that counts up from the start; Stop, the end
 * of the clip, or the end of the call opens the microphone again. Up to
 * [Clips.SLOTS], each imported from a file: a WAV, or raw 16-bit mono PCM at
 * the rate chosen here.
 */
@Composable
fun ClipsScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val c by vm.clips.state.collectAsState()
    val haptics = LocalHapticFeedback.current
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var rateDialog by rememberSaveable { mutableStateOf(false) }
    var voiceDialog by rememberSaveable { mutableStateOf(false) }
    var pitchDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.clips.opened() }

    // A system picker, so the app needs no storage permission: it is handed
    // the files the user chose and nothing else. A .pcm has no type of its
    // own, so every kind is offered.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.clips.importFiles(uris)
    }

    if (rateDialog) {
        ChoiceDialog(
            title = "Raw .pcm files are at",
            intro = "A raw .pcm file has no header to say how fast it was recorded: 16-bit mono, " +
                "at this rate. WAV files say it themselves, and are read as they are.",
            choices = Clips.RAW_RATES,
            selected = c.rawRate,
            label = ::rateLabel,
            onChoose = vm.clips::setRawRate,
            onDismiss = { rateDialog = false },
        )
    }

    if (voiceDialog) {
        ChoiceDialog(
            title = "My voice level",
            intro = "How much louder your microphone is made when it is mixed with a clip. " +
                "If the caller hears you too quietly, raise it; if you sound harsh or the clip is drowned, lower it.",
            choices = Clips.VOICE_LEVELS,
            selected = c.voiceTenths,
            label = ::voiceLabel,
            onChoose = vm.clips::setVoiceTenths,
            onDismiss = { voiceDialog = false },
        )
    }

    if (pitchDialog) {
        ChoiceDialog(
            title = "Voice pitch",
            intro = "How far the voice changer moves your voice, in semitones. Higher makes it " +
                "lighter, lower makes it deeper. It is a simple effect and sounds a little rough; " +
                "try the steps to find the one that suits your voice.",
            choices = Clips.PITCH_STEPS,
            selected = c.pitchSteps,
            label = ::pitchLabel,
            onChoose = vm.clips::setPitchSteps,
            onDismiss = { pitchDialog = false },
        )
    }

    editing?.let { slot ->
        val clip = c.clips.firstOrNull { it.slot == slot }
        if (clip == null) {
            editing = null
        } else {
            var name by rememberSaveable(slot) { mutableStateOf(clip.name) }
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text("Clip") },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { haptics.confirm(); vm.clips.rename(slot, name); editing = null },
                        enabled = name.isNotBlank(),
                    ) { Text("Save") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { haptics.tap(); vm.clips.delete(slot); editing = null }) { Text("Delete") }
                        TextButton(onClick = { editing = null }) { Text("Cancel") }
                    }
                },
            )
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Play a sound into the call you are on. Normally the microphone is muted while it plays, " +
                "so the caller hears the clip and not the room, and it opens again when the clip " +
                "ends, when you tap Stop, or when the call ends. Switch on Keep my voice on to " +
                "talk over the clip instead. Phone calls only.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (c.clips.isEmpty()) {
            Text(
                "No clips yet. Add up to ${Clips.SLOTS}, each up to ${Clips.MAX_SECONDS} seconds.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            SettingsGroup("Clips") {
                c.clips.forEach { clip ->
                    row(key = clip.slot) {
                        val playing = c.playingSlot == clip.slot
                        SettingsRow(
                            title = clip.name,
                            summary = if (playing) {
                                "Playing ${Clips.clock(c.elapsedMs)} / ${Clips.clock(c.totalMs)}"
                            } else {
                                Clips.clock((clip.seconds * 1000).toLong())
                            },
                            onClick = { editing = clip.slot },
                            trailing = {
                                FilledTonalButton(
                                    onClick = {
                                        haptics.tap()
                                        if (playing) vm.clips.stop() else vm.clips.play(clip.slot)
                                    },
                                    enabled = state.daemonRunning,
                                ) { ButtonLabel(if (playing) "Stop" else "Play") }
                            },
                        )
                    }
                }
            }
        }

        val playingSlot = c.playingSlot
        if (playingSlot != null && c.totalMs > 0) {
            LinearProgressIndicator(
                progress = { (c.elapsedMs.toFloat() / c.totalMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        c.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (c.problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsGroup("Playing") {
            row {
                SwitchRow(
                    title = "Keep my voice on",
                    checked = c.mix,
                    onCheckedChange = vm.clips::setMix,
                    summary = if (c.mix) {
                        "The caller hears your microphone and the clip together"
                    } else {
                        "Off: your microphone is muted while a clip plays"
                    },
                )
            }
            row {
                SwitchRow(
                    title = "Change my voice",
                    checked = c.voiceLive,
                    onCheckedChange = vm.clips::setVoiceLive,
                    enabled = state.daemonRunning,
                    summary = if (c.voiceLive) {
                        "On: the caller hears your voice changed (${pitchLabel(c.pitchSteps)})"
                    } else {
                        "Off. Switch on during a phone call; it ends with the call"
                    },
                )
            }
            row {
                SettingsRow(
                    title = "Voice pitch",
                    summary = pitchLabel(c.pitchSteps),
                    onClick = { pitchDialog = true },
                )
            }
            if (c.mix || c.voiceLive) {
                row {
                    SettingsRow(
                        title = "My voice level",
                        summary = voiceLabel(c.voiceTenths),
                        onClick = { voiceDialog = true },
                    )
                }
            }
        }
        SettingsGroup("Adding clips") {
            row {
                SettingsRow(
                    title = "Raw .pcm files",
                    summary = "Read as 16-bit mono at ${rateLabel(c.rawRate)}",
                    onClick = { rateDialog = true },
                )
            }
        }
        ButtonPair(listOf("Add clips"), Modifier.padding(top = 8.dp)) { _, buttonModifier ->
            FilledTonalButton(
                onClick = { haptics.tap(); picker.launch(arrayOf("*/*")) },
                enabled = !c.importing && c.clips.size < Clips.SLOTS,
                modifier = buttonModifier,
            ) { ButtonLabel(if (c.importing) "Adding..." else "Add clips") }
        }
        if (!state.daemonRunning) {
            Text(
                "The recorder is not running, so clips cannot be played. Start it from the home screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The clips in a few words, for their row in Settings. */
internal fun clipsSummary(count: Int): String = when (count) {
    0 -> "Sounds to play into a call"
    1 -> "1 clip"
    else -> "$count clips"
}

/** 10 as "1x (as it is)", 30 as "3x". */
internal fun voiceLabel(tenths: Int): String =
    if (tenths == 10) "1x (as it is)" else "${tenths / 10.0}x".replace(".0x", "x")

/** 4 as "Higher (+4)", -4 as "Lower (-4)": semitones, with what they do. */
internal fun pitchLabel(steps: Int): String = when {
    steps >= 8 -> "Much higher (+$steps)"
    steps > 0 -> "Higher (+$steps)"
    steps < 0 && steps <= -8 -> "Much lower ($steps)"
    steps < 0 -> "Lower ($steps)"
    else -> "As it is (0)"
}

/** 8000 as "8 kHz", 22050 as "22.05 kHz". */
internal fun rateLabel(rate: Int): String = "${rate / 1000.0} kHz".replace(".0 kHz", " kHz")
