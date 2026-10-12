package com.petwalk.photosorter

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel: SorterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoSorterTheme { SorterScreen(viewModel) }
        }
    }
}

private val PHOTO_PERMISSIONS: Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    else -> arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
}

private fun Context.granted(permission: String) =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun Context.hasPhotoAccess() =
    PHOTO_PERMISSIONS.filter { it != Manifest.permission.ACCESS_MEDIA_LOCATION }.any { granted(it) }

private fun Context.hasLocationAccess() = granted(Manifest.permission.ACCESS_MEDIA_LOCATION)

@Composable
fun PhotoSorterTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun SorterScreen(viewModel: SorterViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var hasAccess by remember { mutableStateOf(context.hasPhotoAccess()) }
    var hasLocation by remember { mutableStateOf(context.hasLocationAccess()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasAccess = context.hasPhotoAccess()
        hasLocation = context.hasLocationAccess()
    }
    val moveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.moveAll()
    }

    LaunchedEffect(hasAccess) { if (hasAccess) viewModel.loadAlbums() }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Photo Sorter", style = MaterialTheme.typography.headlineMedium) }

            if (!hasAccess) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Photo Sorter needs access to your photos to sort them into albums.")
                            Button(onClick = { permissionLauncher.launch(PHOTO_PERMISSIONS) }) {
                                Text("Allow photo access")
                            }
                        }
                    }
                }
                return@LazyColumn
            }

            item {
                Controls(
                    state = state,
                    viewModel = viewModel,
                    hasLocation = hasLocation,
                    onRequestLocation = { permissionLauncher.launch(PHOTO_PERMISSIONS) },
                )
            }

            if (state.busy) {
                item { LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth()) }
            }
            state.status?.let { status -> item { Text(status, style = MaterialTheme.typography.bodyMedium) } }

            if (state.groups.isNotEmpty() && !state.busy) {
                item {
                    Actions(
                        onCopy = viewModel::copyAll,
                        onMove = {
                            viewModel.writeRequest()?.let {
                                moveLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build())
                            }
                        },
                    )
                }
            }

            items(state.groups, key = { it.name }) { group -> GroupCard(group, viewModel.store) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Controls(
    state: UiState,
    viewModel: SorterViewModel,
    hasLocation: Boolean,
    onRequestLocation: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Photos from", style = MaterialTheme.typography.labelLarge)
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { menuOpen = true }, enabled = !state.busy) {
                Text(state.album?.let { "${it.name} (${it.count})" } ?: "No photos found")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                state.albums.forEach { album ->
                    DropdownMenuItem(
                        text = { Text("${album.name} (${album.count})") },
                        onClick = {
                            viewModel.setAlbum(album)
                            menuOpen = false
                        },
                    )
                }
            }
        }

        Text("Sort", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mode.entries.forEach { mode ->
                FilterChip(
                    selected = state.mode == mode,
                    onClick = { viewModel.setMode(mode) },
                    label = { Text(mode.label) },
                    enabled = !state.busy,
                )
            }
        }

        when (state.mode) {
            Mode.LOCATION -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Granularity.entries.forEach { g ->
                        FilterChip(
                            selected = state.granularity == g,
                            onClick = { viewModel.setGranularity(g) },
                            label = { Text(g.label) },
                            enabled = !state.busy,
                        )
                    }
                }
                if (!hasLocation) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Location access is off, so every photo will look like it has no location.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRequestLocation) { Text("Allow") }
                    }
                }
            }
            Mode.CONTENT -> {
                Text(
                    "Minimum confidence: ${(state.minConfidence * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = state.minConfidence,
                    onValueChange = viewModel::setMinConfidence,
                    valueRange = 0.3f..0.95f,
                    enabled = !state.busy,
                )
                OutlinedTextField(
                    value = state.focusLabels,
                    onValueChange = viewModel::setFocusLabels,
                    label = { Text("Only these labels (optional)") },
                    placeholder = { Text("Dog, Cat, Beach, Food") },
                    supportingText = { Text("Comma-separated. Leave empty to use each photo's best label.") },
                    singleLine = true,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Button(
            onClick = viewModel::analyze,
            enabled = !state.busy && state.album != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Preview albums") }
    }
}

@Composable
private fun Actions(onCopy: () -> Unit, onMove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Albums are created in ${PhotoStore.OUTPUT_DIR}. Copy keeps your originals where they are; " +
                "move takes them out of their current album.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("Copy into albums") }
            OutlinedButton(
                onClick = onMove,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                modifier = Modifier.weight(1f),
            ) { Text("Move into albums") }
        }
    }
}

@Composable
private fun GroupCard(group: Group, store: PhotoStore) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(group.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${group.photos.size} photo${if (group.photos.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(group.photos.take(30), key = { it.uri.toString() }) { photo -> Thumbnail(photo, store) }
            }
        }
    }
}

@Composable
private fun Thumbnail(photo: Photo, store: PhotoStore) {
    val bitmap by produceState<Bitmap?>(null, photo.uri) {
        value = withContext(Dispatchers.IO) { store.thumbnail(photo.uri, 192) }
    }
    Box(
        Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = photo.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
