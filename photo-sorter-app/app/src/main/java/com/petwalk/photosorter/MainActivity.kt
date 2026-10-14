package com.petwalk.photosorter

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The system folder picker: the app only gets access to the folder chosen here.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setFolder(uri)
    }
    val pickFolder = { folderPicker.launch(state.folder ?: DCIM_HINT) }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Text("Photo Sorter", style = MaterialTheme.typography.headlineMedium)
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                }
            }

            item { FolderCard(state.folder != null, state.folderName, enabled = !state.busy, onPick = pickFolder) }

            if (state.folder != null) {
                item { Controls(state = state, viewModel = viewModel) }
            }

            if (state.busy) {
                item { LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth()) }
            }
            state.status?.let { status -> item { Text(status, style = MaterialTheme.typography.bodyMedium) } }

            if (state.groups.isNotEmpty() && !state.busy) {
                item { Actions(state.folderName, onCopy = viewModel::copyAll, onMove = viewModel::moveAll) }
            }

            items(state.groups, key = { it.name }) { group -> GroupCard(group, viewModel.store) }
        }
    }
}

// Where the folder picker opens the first time: the phone's DCIM (camera) folder.
private val DCIM_HINT: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:DCIM")

@Composable
private fun FolderCard(hasFolder: Boolean, folderName: String?, enabled: Boolean, onPick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasFolder) {
                Text("Folder to sort", style = MaterialTheme.typography.labelLarge)
                Text(folderName ?: "…", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Photo Sorter can only see this folder. Albums are created inside it, " +
                        "in a \"${PhotoStore.OUTPUT_DIR}\" folder.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onPick, enabled = enabled) { Text("Choose a different folder") }
            } else {
                Text(
                    "Choose the folder you want to sort, for example DCIM/Camera. " +
                        "Photo Sorter only gets access to that folder, nothing else on your phone.",
                )
                Button(onClick = onPick) { Text("Choose folder") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Controls(state: UiState, viewModel: SorterViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
            Mode.CONTENT -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Engine.entries.forEach { engine ->
                        FilterChip(
                            selected = state.engine == engine,
                            onClick = { viewModel.setEngine(engine) },
                            label = { Text(engine.label) },
                            enabled = !state.busy,
                        )
                    }
                }
                OutlinedTextField(
                    value = state.categories,
                    onValueChange = viewModel::setCategories,
                    label = { Text("Categories") },
                    supportingText = { Text("Comma-separated, in your own words, e.g. \"my dog, receipts, beach\".") },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (state.engine) {
                    Engine.ON_DEVICE -> {
                        Text(
                            "Minimum confidence: ${(state.minConfidence * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = state.minConfidence,
                            onValueChange = viewModel::setMinConfidence,
                            valueRange = 0.1f..0.9f,
                            enabled = !state.busy,
                        )
                        Text(
                            "Photos below this confidence go to \"${Folders.OTHER}\". Recognition runs entirely " +
                                "on your phone, offline; the first run takes a moment to unpack the model.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Engine.CLAUDE -> {
                        OutlinedTextField(
                            value = state.apiKey,
                            onValueChange = viewModel::setApiKey,
                            label = { Text("Claude API key") },
                            placeholder = { Text("sk-ant-…") },
                            supportingText = { Text("Create one at console.anthropic.com. It's stored only on this phone.") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ClaudeModel.entries.forEach { model ->
                                FilterChip(
                                    selected = state.claudeModel == model,
                                    onClick = { viewModel.setClaudeModel(model) },
                                    label = { Text(model.label) },
                                    enabled = !state.busy,
                                )
                            }
                        }
                        Text(state.claudeModel.note, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "A reduced copy of each photo (768 px) is sent to Anthropic's API, and the cost is " +
                                "billed to your API key. Costs are rough estimates.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = state.limit,
            onValueChange = viewModel::setLimit,
            label = { Text("Photos to review") },
            placeholder = { Text("All") },
            supportingText = { Text("Leave empty to review every photo in the folder.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true to "Oldest first", false to "Newest first").forEach { (oldest, label) ->
                FilterChip(
                    selected = state.oldestFirst == oldest,
                    onClick = { viewModel.setOldestFirst(oldest) },
                    label = { Text(label) },
                    enabled = !state.busy,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.skipSorted,
                onCheckedChange = viewModel::setSkipSorted,
                enabled = !state.busy,
            )
            Text("Skip photos already sorted", style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = viewModel::analyze,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Preview albums") }
    }
}

@Composable
private fun Actions(folderName: String?, onCopy: () -> Unit, onMove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Albums are created in ${folderName ?: "the folder"}/${PhotoStore.OUTPUT_DIR}. Copy keeps your " +
                "originals where they are; move takes them out of the folder and into the albums.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("Copy into albums") }
            OutlinedButton(onClick = onMove, modifier = Modifier.weight(1f)) { Text("Move into albums") }
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
