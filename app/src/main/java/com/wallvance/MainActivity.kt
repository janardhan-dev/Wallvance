
package com.wallvance

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.wallvance.ui.theme.WallvanceTheme

import java.text.DateFormat
import java.util.Date

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS = "wallvance_prefs"
        private const val KEY_IMAGES = "images"
    }

    private var selectedImages by mutableStateOf<List<Uri>>(emptyList())
    private var previewReadyCount by mutableStateOf(0)
    private var previewsPreparing by mutableStateOf(false)
    private var thumbnailJob: Job? = null
    private val thumbnailScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val imagePicker =
        registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia()
        ) { uris ->
            if (uris.isNotEmpty()) {
                selectedImages = (selectedImages + uris).distinct()

                uris.forEach { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                }

                saveImages()
                startThumbnailPreparation(selectedImages)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSavedImages()
        startThumbnailPreparation(selectedImages)

        setContent {
            WallvanceTheme {
                WallvanceApp(
                    selectedImages = selectedImages,
                    previewReadyCount = previewReadyCount,
                    previewsPreparing = previewsPreparing,
                    onSelectImages = {
                        imagePicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    onClearImages = {
                        selectedImages = emptyList()
                        thumbnailJob?.cancel()
                        previewReadyCount = 0
                        previewsPreparing = false
                        saveImages()
                    },
                    onRemoveImage = { uri ->
                        selectedImages = selectedImages.filterNot { it == uri }
                        saveImages()
                        startThumbnailPreparation(selectedImages)
                    }
                )
            }
        }
    }

    private fun startThumbnailPreparation(images: List<Uri>) {
        thumbnailJob?.cancel()

        if (images.isEmpty()) {
            previewReadyCount = 0
            previewsPreparing = false
            return
        }

        previewReadyCount = 0
        previewsPreparing = true

        thumbnailJob = thumbnailScope.launch {
            var ready = 0

            images.forEach { uri ->
                if (!isActive) return@launch

                if (createThumbnailCache(applicationContext, uri)) {
                    ready++
                }

                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    previewReadyCount = ready
                }
            }

            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (isActive) {
                    previewReadyCount = ready
                    previewsPreparing = false
                }
            }
        }
    }

    override fun onDestroy() {
        thumbnailJob?.cancel()
        thumbnailScope.cancel()
        super.onDestroy()
    }

    private fun prefs() =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadSavedImages() {
        val saved = prefs().getString(KEY_IMAGES, null)

        if (!saved.isNullOrBlank()) {
            selectedImages =
                saved.split("\n")
                    .filter { it.isNotBlank() }
                    .map { Uri.parse(it) }
        }
    }

    private fun saveImages() {
        prefs().edit()
            .putString(
                KEY_IMAGES,
                selectedImages.joinToString("\n") { it.toString() }
            )
            .apply()
    }
}

@Composable
fun WallvanceApp(
    selectedImages: List<Uri>,
    previewReadyCount: Int,
    previewsPreparing: Boolean,
    onSelectImages: () -> Unit,
    onClearImages: () -> Unit,
    onRemoveImage: (Uri) -> Unit
) {

    val context = LocalContext.current

    val prefs = remember {
        context.getSharedPreferences(
            "wallvance_prefs",
            Context.MODE_PRIVATE
        )
    }

    var interval by remember {
        mutableStateOf(
            prefs.getString("interval", "30 minutes") ?: "30 minutes"
        )
    }

    var order by remember {
        mutableStateOf(
            prefs.getString("order", "Random") ?: "Random"
        )
    }

    var target by remember {
        mutableStateOf(
            prefs.getString("target", "Both") ?: "Both"
        )
    }

    var running by remember {
        mutableStateOf(
            prefs.getBoolean("running", false)
        )
    }

    var customValue by remember {
        mutableStateOf(
            prefs.getString("custom_value", "30") ?: "30"
        )
    }

    var customUnit by remember {
        mutableStateOf(
            prefs.getString("custom_unit", "Minutes") ?: "Minutes"
        )
    }

    var customConfirmed by remember {
        mutableStateOf(
            prefs.getBoolean("custom_confirmed", false)
        )
    }

    var showIntervalOptions by remember { mutableStateOf(false) }
    var showOrderOptions by remember { mutableStateOf(false) }
    var showTargetOptions by remember { mutableStateOf(false) }
    var showMoreComing by remember { mutableStateOf(false) }
    var showExactAlarmDialog by remember { mutableStateOf(false) }

    var lastChanged by remember {
        mutableStateOf(
            prefs.getLong("last_changed_time", 0L)
        )
    }

    var nextChange by remember {
        mutableStateOf(
            prefs.getLong("next_change_time", 0L)
        )
    }

    fun exactAlarmAllowed(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        } else {
            true
        }
    }

    fun openExactAlarmSettings() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            } catch (_: Exception) {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!exactAlarmAllowed() && !prefs.getBoolean("exact_alarm_prompt_shown", false)) {
            showExactAlarmDialog = true
            prefs.edit().putBoolean("exact_alarm_prompt_shown", true).apply()
        }
    }

    val intervals = listOf(
        "15 minutes",
        "30 minutes",
        "1 hour",
        "6 hours",
        "12 hours",
        "24 hours",
        "Custom"
    )

    fun refreshSchedule() {
        lastChanged = prefs.getLong("last_changed_time", 0L)
        nextChange = prefs.getLong("next_change_time", 0L)
    }

    fun saveSettings() {
        prefs.edit()
            .putString("interval", interval)
            .putString("order", order)
            .putString("target", target)
            .putString("custom_value", customValue)
            .putString("custom_unit", customUnit)
            .putBoolean("custom_confirmed", customConfirmed)
            .apply()
    }

    LaunchedEffect(Unit) {
        while (true) {
            refreshSchedule()
            running = prefs.getBoolean("running", running)
            delay(1000)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {

            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .zIndex(1f),
                color = MaterialTheme.colorScheme.background
            ) {
                HeaderSection()
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
                contentPadding = PaddingValues(
                    top = 102.dp,
                    bottom = 145.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                item {
                    SelectedWallpapersSection(
                        selectedImages = selectedImages,
                        previewReadyCount = previewReadyCount,
                        previewsPreparing = previewsPreparing,
                        onSelectImages = onSelectImages,
                        onClearImages = onClearImages,
                        onRemoveImage = onRemoveImage
                    )
                }

                item {
                    AutomationStatusCard(
                        running = running,
                        nextChange = nextChange,
                        lastChanged = lastChanged
                    )
                }

                item {
                    Text(
                        text = "Automation settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                }

                item {
                    ExpandableSettingCard(
                        title = "Change every",
                        value =
                            if (interval == "Custom" && customConfirmed) {
                                "$customValue ${customUnit.lowercase()}"
                            } else {
                                interval
                            },
                        expanded = showIntervalOptions,
                        onClick = {
                            showIntervalOptions = !showIntervalOptions
                            showOrderOptions = false
                            showTargetOptions = false
                        }
                    ) {
                        Column {
                            intervals.forEach { option ->
                                CompactRadioRow(
                                    label = option,
                                    selected = interval == option,
                                    onClick = {
                                        interval = option
                                        customConfirmed = false
                                        saveSettings()

                                        if (option != "Custom") {
                                            showIntervalOptions = false
                                        }
                                    }
                                )
                            }

                            AnimatedVisibility(
                                visible = interval == "Custom",
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column {
                                    Spacer(Modifier.height(8.dp))

                                    OutlinedTextField(
                                        value = customValue,
                                        onValueChange = { value ->
                                            if (value.all { it.isDigit() }) {
                                                customValue = value
                                                saveSettings()
                                            }
                                        },
                                        label = { Text("Custom interval (minimum 5 minutes)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(Modifier.height(10.dp))

                                    Text(
                                        text = "Unit",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Spacer(Modifier.height(6.dp))

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf("Minutes", "Hours", "Days").forEach { unit ->
                                            FilterChip(
                                                selected = customUnit == unit,
                                                onClick = {
                                                    customUnit = unit
                                                    saveSettings()
                                                },
                                                label = { Text(unit) }
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(10.dp))

                                    Button(
                                        onClick = {
                                            if (
                                                customValue.toLongOrNull()
                                                    ?.let { value ->
                                                        if (customUnit == "Minutes") value >= 5L else value >= 1L
                                                    } == true
                                            ) {
                                                customConfirmed = true
                                                saveSettings()
                                                showIntervalOptions = false
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text("Done")
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    ExpandableSettingCard(
                        title = "Wallpaper order",
                        value = order,
                        expanded = showOrderOptions,
                        onClick = {
                            showOrderOptions = !showOrderOptions
                            showIntervalOptions = false
                            showTargetOptions = false
                        }
                    ) {
                        Column {
                            CompactRadioRow(
                                label = "Random",
                                selected = order == "Random",
                                onClick = {
                                    order = "Random"
                                    saveSettings()
                                    showOrderOptions = false
                                }
                            )

                            CompactRadioRow(
                                label = "In Order",
                                selected = order == "In Order",
                                onClick = {
                                    order = "In Order"
                                    saveSettings()
                                    showOrderOptions = false
                                }
                            )
                        }
                    }
                }

                item {
                    ExpandableSettingCard(
                        title = "Apply to",
                        value = when (target) {
                            "Home screen" -> "Home screen"
                            "Lock screen" -> "Lock screen"
                            else -> "Both (Home & Lock)"
                        },
                        expanded = showTargetOptions,
                        onClick = {
                            showTargetOptions = !showTargetOptions
                            showIntervalOptions = false
                            showOrderOptions = false
                        }
                    ) {
                        Column {
                            CompactRadioRow(
                                label = "Home screen",
                                selected = target == "Home screen",
                                onClick = {
                                    target = "Home screen"
                                    saveSettings()
                                    showTargetOptions = false
                                }
                            )

                            CompactRadioRow(
                                label = "Lock screen",
                                selected = target == "Lock screen",
                                onClick = {
                                    target = "Lock screen"
                                    saveSettings()
                                    showTargetOptions = false
                                }
                            )

                            CompactRadioRow(
                                label = "Both",
                                selected = target == "Both",
                                onClick = {
                                    target = "Both"
                                    saveSettings()
                                    showTargetOptions = false
                                }
                            )
                        }
                    }
                }

                item {
                    MoreComingCard(
                        expanded = showMoreComing,
                        onClick = {
                            showMoreComing = !showMoreComing
                        }
                    )
                }

                item {
                    PrivacySection()
                }

                item {
                    CommunitySection()
                }
            }

            BottomAutomationBar(
                running = running,
                nextChange = nextChange,
                enabled = running || selectedImages.isNotEmpty(),
                onClick = {
                    if (!running) {
                        if (selectedImages.isNotEmpty()) {
                            if (!exactAlarmAllowed()) {
                                showExactAlarmDialog = true
                            } else {
                                WallpaperEngine.start(
                                    context = context,
                                    images = selectedImages,
                                    interval = interval,
                                    order = order,
                                    target = target,
                                    customValue = customValue,
                                    customUnit = customUnit
                                )

                                prefs.edit()
                                    .putBoolean("running", true)
                                    .apply()

                                saveSettings()
                                running = true
                                refreshSchedule()
                            }
                        }
                    } else {
                        WallpaperEngine.stop(context)

                        prefs.edit()
                            .putBoolean("running", false)
                            .apply()

                        running = false
                        refreshSchedule()
                    }
                }
            )

            if (showExactAlarmDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showExactAlarmDialog = false },
                    title = {
                        Text("Allow precise wallpaper scheduling?")
                    },
                    text = {
                        Text(
                            "Wallvance uses Android's exact alarms to change your wallpapers at the time you choose. " +
                                    "Without this permission, wallpaper changes may happen later than scheduled."
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showExactAlarmDialog = false
                                openExactAlarmSettings()
                            }
                        ) {
                            Text("Allow")
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { showExactAlarmDialog = false }
                        ) {
                            Text("Not now")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun HeaderSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Wallvance",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(Modifier.height(2.dp))

                Text(
                    text = "Make your screen move with you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "⋮",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
    }
}

@Composable
private fun SelectedWallpapersSection(
    selectedImages: List<Uri>,
    previewReadyCount: Int,
    previewsPreparing: Boolean,
    onSelectImages: () -> Unit,
    onClearImages: () -> Unit,
    onRemoveImage: (Uri) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Selected wallpapers",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(Modifier.height(3.dp))

                    Text(
                        text = if (selectedImages.isEmpty()) {
                            "No wallpapers selected"
                        } else {
                            "${selectedImages.size} wallpapers selected"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AccentPillButton(
                    text = "+ Add",
                    onClick = onSelectImages
                )
            }

            if (selectedImages.isEmpty()) {
                EmptyWallpaperTile(onClick = onSelectImages)
            } else if (previewsPreparing || previewReadyCount < selectedImages.size) {
                PreviewPreparationTile(
                    ready = previewReadyCount,
                    total = selectedImages.size
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 1.dp)
                ) {
                    items(
                        items = selectedImages,
                        key = { it.toString() }
                    ) { uri ->
                        WallpaperPreviewTile(
                            uri = uri,
                            onRemove = { onRemoveImage(uri) }
                        )
                    }
                }

                Button(
                    onClick = onClearImages,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text("Clear all")
                }
            }
        }
    }
}

@Composable
private fun WallpaperPreviewTile(
    uri: Uri,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) {
            loadCachedThumbnail(context, uri)
        }
    }

    Box(
        modifier = Modifier
            .size(width = 76.dp, height = 104.dp)
            .clip(RoundedCornerShape(18.dp))
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Selected wallpaper",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Wallpaper",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(23.dp)
                .clickable(onClick = onRemove),
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.68f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "×",
                    fontSize = 16.sp,
                    lineHeight = 16.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun PreviewPreparationTile(
    ready: Int,
    total: Int
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Preparing previews…",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$ready / $total ready",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun thumbnailFile(context: Context, uri: Uri): File {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(uri.toString().toByteArray())
        .joinToString("") { "%02x".format(it) }

    return File(File(context.cacheDir, "wallvance_thumbnails"), "$digest.jpg")
}

private fun createThumbnailCache(context: Context, uri: Uri): Boolean {
    val directory = File(context.cacheDir, "wallvance_thumbnails")
    if (!directory.exists()) directory.mkdirs()

    val outputFile = thumbnailFile(context, uri)
    if (outputFile.exists() && outputFile.length() > 0L) return true

    return try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            BitmapFactory.decodeFileDescriptor(
                descriptor.fileDescriptor,
                null,
                bounds
            )
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        val targetWidth = 120
        val targetHeight = 170
        var sample = 1

        while (
            bounds.outWidth / (sample * 2) >= targetWidth &&
            bounds.outHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        val bitmap = resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            BitmapFactory.decodeFileDescriptor(
                descriptor.fileDescriptor,
                null,
                options
            )
        } ?: return false

        val tempFile = File(directory, "${outputFile.name}.tmp")
        try {
            FileOutputStream(tempFile).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) {
                    return false
                }
            }
            if (!tempFile.renameTo(outputFile)) {
                tempFile.delete()
                return false
            }
            true
        } finally {
            bitmap.recycle()
            if (tempFile.exists()) tempFile.delete()
        }
    } catch (_: Exception) {
        false
    }
}

private fun loadCachedThumbnail(context: Context, uri: Uri): Bitmap? {
    return try {
        val file = thumbnailFile(context, uri)
        if (!file.exists()) return null

        BitmapFactory.decodeFile(file.absolutePath)
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun AccentPillButton(
    text: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(50.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = 9.dp
            ),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun EmptyWallpaperTile(
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(106.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "+",
                fontSize = 30.sp,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Add wallpapers",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun MoreWallpapersTile(
    count: Int,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(82.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "+$count",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Text(
                text = "more",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun AutomationStatusCard(
    running: Boolean,
    nextChange: Long,
    lastChanged: Long
) {
    val green = Color(0xFF55E39A)
    val red = Color(0xFFFF625F)
    val accent = if (running) green else red

    val animatedAccent by animateColorAsState(
        targetValue = accent,
        animationSpec = tween(300),
        label = "statusAccent"
    )

    val pulseTransition = rememberInfiniteTransition(
        label = "statusPulseTransition"
    )

    val pulse by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (running) 1.14f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1200,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "statusPulse"
    )

    val brush = Brush.linearGradient(
        colors = if (running) {
            listOf(
                Color(0xFF18352F),
                Color(0xFF192B32)
            )
        } else {
            listOf(
                Color(0xFF351E22),
                Color(0xFF232327)
            )
        }
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(brush)
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .graphicsLayerScale(pulse)
                        .clip(CircleShape)
                        .background(animatedAccent)
                )

                Spacer(Modifier.width(10.dp))

                Text(
                    text = if (running) {
                        "Automation is running"
                    } else {
                        "Automation is stopped"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = "Next wallpaper change",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(5.dp))

            if (running && nextChange > 0L) {
                Text(
                    text = formatDateTime(nextChange),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium
                )

                val remaining = nextChange - System.currentTimeMillis()

                Text(
                    text = if (remaining > 0L) {
                        "Changes in ${formatDuration(remaining)}"
                    } else {
                        "Change due"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = animatedAccent,
                    modifier = Modifier.padding(top = 3.dp)
                )
            } else {
                Text(
                    text = "Start automation to schedule changes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(18.dp))

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )

            Spacer(Modifier.height(13.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Last changed",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = if (lastChanged > 0L) {
                        formatDateTime(lastChanged)
                    } else {
                        "Not changed yet"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ExpandableSettingCard(
    title: String,
    value: String,
    expanded: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "settingArrowRotation"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ),
        shape = RoundedCornerShape(24.dp),
        color = if (expanded) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(
                        horizontal = 18.dp,
                        vertical = 16.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(Modifier.height(3.dp))

                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "⌄",
                    fontSize = 22.sp,
                    modifier = Modifier.graphicsLayerRotation(rotation),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(tween(170)),
                exit = shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeOut(tween(120))
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 8.dp,
                            end = 8.dp,
                            bottom = 8.dp
                        ),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp)
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(220),
        label = "radioContainerColor"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = androidx.compose.material3.RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary,
                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

@Composable
private fun MoreComingCard(
    expanded: Boolean,
    onClick: () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "moreArrowRotation"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "✦",
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                Spacer(Modifier.width(13.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "More is coming",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "More ways to make your screen move with you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "⌄",
                    fontSize = 22.sp,
                    modifier = Modifier.graphicsLayerRotation(rotation),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(tween(180)),
                exit = shrinkVertically() + fadeOut(tween(120))
            ) {
                Column(
                    modifier = Modifier.padding(
                        start = 18.dp,
                        end = 18.dp,
                        bottom = 18.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    MoreComingPoint("Location-based automation")
                    MoreComingPoint("Wi-Fi-based automation")
                    MoreComingPoint("Unlock-based wallpaper changes")
                    MoreComingPoint("Smarter schedules and combinations")
                    MoreComingPoint("More customization options")
                }
            }
        }
    }
}

@Composable
private fun PrivacySection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            text = "Privacy",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = "Your wallpapers stay on your device. Wallvance does not require an account.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "Privacy Policy",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    // Replace this with the final hosted Privacy Policy URL
                    // before the Play Store release.
                }
                .padding(horizontal = 2.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun CommunitySection() {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            text = "Community",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = "Share feedback, suggest features, report bugs, and discuss Wallvance with other users.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Surface(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    try {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://t.me/Wallvance")
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
                .padding(horizontal = 2.dp, vertical = 5.dp),
            color = Color.Transparent
        ) {
            Text(
                text = "Join Wallvance Community →",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun MoreComingPoint(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )

        Spacer(Modifier.width(10.dp))

        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun BoxScope.BottomAutomationBar(
    running: Boolean,
    nextChange: Long,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val buttonColor = if (running) {
        Color(0xFFD94B4B)
    } else {
        MaterialTheme.colorScheme.primary
    }

    val animatedButtonColor by animateColorAsState(
        targetValue = buttonColor,
        animationSpec = tween(300),
        label = "automationButtonColor"
    )

    val runningGreen = Color(0xFF55E39A)

    val barBrush = Brush.linearGradient(
        colors = if (running) {
            listOf(
                Color(0xFF18352F),
                Color(0xFF192B32)
            )
        } else {
            listOf(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.colorScheme.surfaceContainerHigh
            )
        }
    )

    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                horizontal = 18.dp,
                vertical = 8.dp
            ),
        shape = RoundedCornerShape(25.dp),
        color = Color.Transparent,
        border = if (running) {
            androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = runningGreen.copy(alpha = 0.32f)
            )
        } else {
            null
        },
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(barBrush, RoundedCornerShape(25.dp))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val bottomPulseTransition = rememberInfiniteTransition(
                label = "bottomAutomationPulseTransition"
            )

            val bottomPulse by bottomPulseTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (running) 1.12f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 1200,
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bottomAutomationPulse"
            )

            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(9.dp)
                    .graphicsLayerScale(bottomPulse)
                    .clip(CircleShape)
                    .background(
                        if (running) runningGreen
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
            )

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (running) {
                        "Automation is running"
                    } else {
                        "Automation is off"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                if (running) {
                    Text(
                        text = if (nextChange > 0L) {
                            "Next: ${formatDuration(nextChange - System.currentTimeMillis())}"
                        } else {
                            "Scheduled"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = runningGreen
                    )
                }
            }

            Button(
                onClick = onClick,
                enabled = enabled,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = animatedButtonColor,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = if (running) "Stop" else "Start",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private fun Modifier.graphicsLayerScale(
    scale: Float
): Modifier {
    return this.scale(scale)
}

private fun Modifier.graphicsLayerRotation(
    rotation: Float
): Modifier {
    return this.rotate(rotation)
}

private fun formatDateTime(timestamp: Long): String {
    val date = Date(timestamp)

    val datePart = DateFormat
        .getDateInstance(DateFormat.MEDIUM)
        .format(date)

    val timePart = DateFormat
        .getTimeInstance(DateFormat.SHORT)
        .format(date)

    return "$datePart • $timePart"
}

private fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "now"

    val totalMinutes =
        (millis / 60_000L).coerceAtLeast(1L)

    return when {
        totalMinutes < 60L -> "$totalMinutes min"

        totalMinutes < 1440L -> {
            val hours = totalMinutes / 60L
            val minutes = totalMinutes % 60L

            if (minutes == 0L) {
                "$hours hr"
            } else {
                "$hours hr $minutes min"
            }
        }

        else -> {
            val days = totalMinutes / 1440L
            val hours = (totalMinutes % 1440L) / 60L

            if (hours == 0L) {
                "$days day"
            } else {
                "$days day $hours hr"
            }
        }
    }
}

