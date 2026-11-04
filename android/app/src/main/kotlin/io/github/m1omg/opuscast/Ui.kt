package io.github.m1omg.opuscast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import io.github.m1omg.opuscast.core.NetDevice
import io.github.m1omg.opuscast.core.RecentItem
import io.github.m1omg.opuscast.core.fmtTime

/** What the screen asks the activity to do (system pickers, settings screens). */
interface UiActions {
    fun chooseVideo()
    fun chooseSubtitleFile()
    fun allowFolder()
    fun allowAllFiles()
    fun setPlayerKeys(on: Boolean)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpusCastScreen(engine: Engine, actions: UiActions) {
    val ui by engine.ui.collectAsStateWithLifecycle()
    var showDevices by remember { mutableStateOf(false) }
    var showSubs by remember { mutableStateOf(false) }
    DisposableEffect(engine) {
        engine.openDevices = { showDevices = true }
        onDispose { engine.openDevices = null }
    }
    LaunchedEffect(showDevices, showSubs) { actions.setPlayerKeys(!showDevices && !showSubs) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), maxLines = 1)
                        Text(ui.statusLine, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    FilledTonalButton(onClick = { showDevices = true }, modifier = Modifier.padding(end = 4.dp)) {
                        Icon(Icons.Filled.Tv, contentDescription = stringResource(R.string.choose_tv), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(ui.tvName ?: stringResource(if (ui.searchingTv) R.string.tv else R.string.no_tv),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    IconButton(onClick = actions::chooseVideo) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = stringResource(R.string.choose_video))
                    }
                },
            )
        },
        bottomBar = { OptionsBar(ui, engine) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ui.banner?.let { BannerView(it, engine) }
            when (ui.page) {
                Page.PICK -> PickPage(ui, engine, actions)
                Page.PLAYER -> ui.player?.let { PlayerPage(it, ui, engine) { showSubs = true } }
            }
        }
    }
    if (showDevices) DeviceSheet(ui, engine) { showDevices = false }
    val menu = ui.player?.subMenu
    if (showSubs && menu != null) SubtitleSheet(menu, engine, actions) { showSubs = false }
    LaunchedEffect(menu == null) { if (menu == null) showSubs = false }
}

// ---------------------------------------------------------------- banner

@Composable
private fun BannerView(b: Banner, engine: Engine) {
    val (bg, fg, icon) = when (b.kind) {
        Banner.Kind.INFO -> Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, Icons.Filled.Info)
        Banner.Kind.WARNING -> Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, Icons.Filled.Warning)
        Banner.Kind.ERROR -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, Icons.Filled.Error)
    }
    Card(colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(b.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = 2.dp))
            IconButton(onClick = engine::clearInfo, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), modifier = Modifier.size(18.dp))
            }
        }
        if (b.actions.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End) {
                for (a in b.actions) TextButton(onClick = { engine.bannerAction(a) }) { Text(a.label, color = fg) }
            }
        } else {
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ---------------------------------------------------------------- pick page

@Composable
private fun PickPage(ui: UiState, engine: Engine, actions: UiActions) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Movie, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.pick_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.pick_sub), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = actions::chooseVideo, modifier = Modifier.widthIn(min = 220.dp).height(48.dp)) {
                        Text(stringResource(R.string.choose_video))
                    }
                }
            }
            Text(stringResource(R.string.recent), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp, start = 4.dp))
        }
        if (ui.recent.isEmpty()) {
            item {
                Text(stringResource(R.string.recent_empty), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
        }
        items(ui.recent, key = { it.ref }) { RecentRow(it, engine) }
    }
}

@Composable
private fun RecentRow(item: RecentItem, engine: Engine) {
    val resumable = item.pos > 5 && !item.watched
    val status = when {
        item.watched -> stringResource(R.string.watched)
        item.pos > 5 && item.dur > 0 -> stringResource(R.string.resume_at_of, fmtTime(item.pos), fmtTime(item.dur))
        item.pos > 5 -> stringResource(R.string.resume_at, fmtTime(item.pos))
        else -> stringResource(R.string.not_started)
    }
    ListItem(
        modifier = Modifier.clickable { engine.playRecent(item) },
        leadingContent = { Icon(Icons.Filled.Movie, contentDescription = null) },
        headlineContent = { Text(item.name.substringBeforeLast('.'), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Column {
                Text(status)
                if (resumable && item.dur > 0) {
                    LinearProgressIndicator(progress = { (item.pos / item.dur).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
        },
        trailingContent = {
            Row {
                if (resumable) IconButton(onClick = { engine.playRecent(item, fromStart = true) }) {
                    Icon(Icons.Filled.Replay, contentDescription = stringResource(R.string.play_from_beginning))
                }
                IconButton(onClick = { engine.forget(item.ref) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove_from_list))
                }
            }
        },
    )
}

// ---------------------------------------------------------------- player page

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerPage(p: PlayerUi, ui: UiState, engine: Engine, openSubtitles: () -> Unit) {
    val clock by engine.clock.collectAsStateWithLifecycle()
    val exo by engine.preview.player.collectAsStateWithLifecycle()
    var drag by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        val player = exo
        if (ui.previewOn && player != null) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black, RoundedCornerShape(12.dp))) {
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { useController = false } },
                    update = { it.player = player },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Column(Modifier.fillMaxWidth().aspectRatio(16f / 9f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.Tv, contentDescription = null, modifier = Modifier.size(88.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                if (p.tvName != null) Text(stringResource(R.string.playing_on, p.tvName), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(p.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(p.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))

        val dur = clock.dur.toFloat()
        val value = drag ?: clock.pos.toFloat()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmtTime(value.toDouble()), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = if (dur > 0) value.coerceIn(0f, dur) else 0f,
                onValueChange = { drag = it; engine.scrub(it.toDouble()) },
                onValueChangeFinished = { drag?.let { engine.scrubEnd(it.toDouble()) }; drag = null },
                valueRange = 0f..maxOf(dur, 1f),
                enabled = p.seekable,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            Text(if (dur > 0) fmtTime(clock.dur) else "--:--", style = MaterialTheme.typography.labelMedium)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SkipButton(Icons.Filled.Replay30, R.string.back_30) { engine.skip(-30.0) }
            SkipButton(Icons.Filled.Replay10, R.string.back_10) { engine.skip(-10.0) }
            FilledIconButton(onClick = engine::togglePlay, modifier = Modifier.padding(horizontal = 8.dp).size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors()) {
                Icon(if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.play_pause),
                    modifier = Modifier.size(40.dp))
            }
            SkipButton(Icons.Filled.Forward10, R.string.forward_10) { engine.skip(10.0) }
            SkipButton(Icons.Filled.Forward30, R.string.forward_30) { engine.skip(30.0) }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = engine::stop) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.stop))
            }
            if (p.showSubtitles) OutlinedButton(onClick = openSubtitles) {
                Icon(Icons.Filled.Subtitles, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(p.subLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            }
            if (p.isIntro) FilledTonalButton(onClick = engine::skipIntro) {
                Text(stringResource(R.string.skip_intro))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        val volume = ui.volume
        if (volume != null) {
            var volDrag by remember { mutableStateOf<Float?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(R.string.tv_volume))
                Slider(
                    value = volDrag ?: volume.toFloat(),
                    onValueChange = { volDrag = it; engine.setVolume(it.toInt()) },
                    onValueChangeFinished = { volDrag = null },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                Text("${(volDrag ?: volume.toFloat()).toInt()}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(28.dp))
            }
        }
        Text(stringResource(R.string.keep_wifi), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun SkipButton(icon: ImageVector, label: Int, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Icon(icon, contentDescription = stringResource(label), modifier = Modifier.size(28.dp))
    }
}

// ---------------------------------------------------------------- options bar

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsBar(ui: UiState, engine: Engine) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.Center) {
                Toggle(stringResource(R.string.show_on_phone), ui.previewOn) { engine.setPreview(it) }
                Toggle(stringResource(R.string.skynet_intro), ui.introOn) { engine.setIntro(it) }
            }
            ui.introProgress?.let { p ->
                Text(stringResource(R.string.preparing_intro_pct, (p * 100).toInt()), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.selectable(selected = on, role = Role.Switch) { onChange(!on) }.padding(vertical = 4.dp)) {
        Switch(checked = on, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------- device list

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSheet(ui: UiState, engine: Engine, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) { engine.scan() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.devices_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                if (ui.scanning) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                IconButton(onClick = { engine.scan(force = true) }, enabled = !ui.scanning) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.scan_again))
                }
            }
            if (ui.devices.isEmpty()) {
                Text(stringResource(R.string.scanning), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(ui.devices, key = { it.ip }) { d -> DeviceRow(d, ui, engine, onDismiss) }
                item {
                    Text(stringResource(R.string.devices_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
                    HorizontalDivider()
                    AddressEntry(engine, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(d: NetDevice, ui: UiState, engine: Engine, onDismiss: () -> Unit) {
    val icon = when (d.kind) {
        NetDevice.Kind.TV -> Icons.Filled.Tv
        NetDevice.Kind.SPEAKER -> Icons.Filled.Speaker
        NetDevice.Kind.ROUTER -> Icons.Filled.Router
        NetDevice.Kind.THIS_DEVICE -> Icons.Filled.PhoneAndroid
        NetDevice.Kind.UPNP -> Icons.Filled.Dns
        NetDevice.Kind.UNKNOWN -> Icons.Filled.DeviceUnknown
    }
    val name = d.name ?: stringResource(when (d.kind) {
        NetDevice.Kind.THIS_DEVICE -> R.string.this_phone
        NetDevice.Kind.ROUTER -> R.string.router
        else -> R.string.unknown_device
    })
    val (note, noteColor) = when (d.note) {
        NetDevice.Note.CAN_PLAY_VIDEO -> "✓ " + stringResource(R.string.can_play_video) to Color(0xFF26A269)
        NetDevice.Note.AUDIO_ONLY -> "♪ " + stringResource(R.string.audio_only) to Color(0xFFC88800)
        NetDevice.Note.ROUTER -> "✗ " + stringResource(R.string.router_cant) to MaterialTheme.colorScheme.onSurfaceVariant
        NetDevice.Note.MEDIA_SERVER -> "✗ " + stringResource(R.string.media_server_cant) to MaterialTheme.colorScheme.onSurfaceVariant
        NetDevice.Note.NETWORK_DEVICE -> "✗ " + stringResource(R.string.network_device_cant) to MaterialTheme.colorScheme.onSurfaceVariant
        NetDevice.Note.NO_CASTING -> "✗ " + stringResource(R.string.no_casting) to MaterialTheme.colorScheme.onSurfaceVariant
        NetDevice.Note.THIS_DEVICE -> stringResource(R.string.this_phone) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val tv = d.tv
    val inUse = tv != null && d.ip == ui.tvHost
    ListItem(
        modifier = if (d.canPlay && tv != null) Modifier.clickable { engine.selectTv(tv); onDismiss() } else Modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null, tint = if (d.canPlay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(listOf(d.ip, d.detail).filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(note, color = noteColor, style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (d.canPlay) FontWeight.Bold else FontWeight.Normal)
            }
        },
        trailingContent = {
            when {
                inUse -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.in_use), style = MaterialTheme.typography.labelMedium)
                }
                d.canPlay && tv != null -> FilledTonalButton(onClick = { engine.selectTv(tv); onDismiss() }) {
                    Text(stringResource(R.string.use_this_tv))
                }
            }
        },
    )
}

@Composable
private fun AddressEntry(engine: Engine, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    fun connect() {
        val a = address.trim()
        if (a.isEmpty() || busy) return
        busy = true
        failed = null
        engine.connectTo(a) { ok ->
            busy = false
            if (ok) onDismiss() else failed = a
        }
    }
    Column(Modifier.padding(top = 12.dp)) {
        Text(stringResource(R.string.enter_address), style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            OutlinedTextField(
                value = address, onValueChange = { address = it; failed = null }, singleLine = true,
                placeholder = { Text(stringResource(R.string.address_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { connect() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            if (busy) CircularProgressIndicator(Modifier.size(28.dp)) else Button(onClick = ::connect) { Text(stringResource(R.string.connect)) }
        }
        failed?.let { Text(stringResource(R.string.no_tv_at, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)) }
    }
}

// ---------------------------------------------------------------- subtitles

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitleSheet(menu: SubMenu, engine: Engine, actions: UiActions, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.subtitles), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp))
            if (menu.searching) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.looking_for_subtitles))
                }
            } else {
                for (o in menu.options) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                        .selectable(selected = o.selected, role = Role.RadioButton) { engine.pickSubtitle(o.entry); onDismiss() }
                        .padding(vertical = 2.dp)) {
                        RadioButton(selected = o.selected, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(o.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                for ((label, why) in menu.unusable) {
                    Text("$label - $why", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                }
                if (menu.noneFound) {
                    Text(stringResource(R.string.no_subtitles_found), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = { actions.chooseSubtitleFile(); onDismiss() }) { Text(stringResource(R.string.choose_subtitle_file)) }
            if (menu.folderAccess == FolderAccess.NEED_FOLDER || menu.folderAccess == FolderAccess.NEED_ALL_FILES) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(stringResource(R.string.folder_access_title), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        if (menu.folderAccess == FolderAccess.NEED_FOLDER) {
                            Text(stringResource(R.string.folder_access_text), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { actions.allowFolder(); onDismiss() }, modifier = Modifier.padding(top = 8.dp)) {
                                Text(stringResource(R.string.allow_folder))
                            }
                        } else {
                            Text(stringResource(R.string.all_files_text), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { actions.allowAllFiles(); onDismiss() }, modifier = Modifier.padding(top = 8.dp)) {
                                Text(stringResource(R.string.allow_all_files))
                            }
                        }
                    }
                }
            }
            for (n in menu.notes) {
                Text(n, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
