package io.maru.manime.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.maru.manime.*
import io.maru.manime.extensions.CloudstreamPluginInfo
import io.maru.manime.extensions.CloudstreamRepo
import io.maru.manime.extensions.StremioManifest

@Composable
fun ExtensionsScreen(
    savedRepos: List<CloudstreamRepo>,
    installedCloudstream: List<String>,
    onAddCloudstreamRepo: (String) -> Unit,
    onRemoveCloudstreamRepo: (CloudstreamRepo) -> Unit,
    onInstallCloudstreamPlugin: (CloudstreamPluginInfo) -> Unit,
    onDeleteCloudstreamPlugin: (String) -> Unit,
    stremioAddons: Set<String>,
    onAddStremioAddon: (String) -> Unit,
    onRemoveStremioAddon: (String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Cloudstream", "Stremio", "Aniyomi")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // MAudio Pills Tab Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                Surface(
                    onClick = { selectedTab = index },
                    shape = MaruPillShape,
                    color = if (isSelected) Color(0x33E85D9F) else MaruGlassSubtleBg,
                    border = BorderStroke(1.dp, if (isSelected) MaruAccentPink else MaruGlassBorderSoft),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                letterSpacing = 0.6.sp
                            ),
                            color = if (isSelected) MaruAccentPink else MaruTextMuted
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (selectedTab) {
            0 -> CloudstreamTab(
                savedRepos = savedRepos,
                installed = installedCloudstream,
                onAddRepo = onAddCloudstreamRepo,
                onRemoveRepo = onRemoveCloudstreamRepo,
                onInstallPlugin = onInstallCloudstreamPlugin,
                onDeletePlugin = onDeleteCloudstreamPlugin
            )
            1 -> StremioTab(
                addons = stremioAddons,
                onAddAddon = onAddStremioAddon,
                onRemoveAddon = onRemoveStremioAddon
            )
            2 -> AniyomiTab()
        }
    }
}

@Composable
private fun CloudstreamTab(
    savedRepos: List<CloudstreamRepo>,
    installed: List<String>,
    onAddRepo: (String) -> Unit,
    onRemoveRepo: (CloudstreamRepo) -> Unit,
    onInstallPlugin: (CloudstreamPluginInfo) -> Unit,
    onDeletePlugin: (String) -> Unit
) {
    var repoInput by remember { mutableStateOf("") }
    var selectedRepoUrl by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "add_repo_card") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassSectionHeader(
                        title = "ADD CLOUDSTREAM REPOSITORY",
                        icon = Icons.Default.Extension,
                        color = MaruAccentPink
                    )
                    Text(
                        text = "Enter repo.json URL or shortcode (e.g. megarepo, hexated). Extensions won't be installed automatically—you can choose which ones to install below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaruTextMuted
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = MaruInputShape,
                            color = Color(0x33000000),
                            border = BorderStroke(1.dp, MaruGlassBorderSoft),
                            modifier = Modifier.weight(1f)
                        ) {
                            TextField(
                                value = repoInput,
                                onValueChange = { repoInput = it },
                                placeholder = { Text("URL or shortcode", color = MaruTextMuted.copy(alpha = 0.6f), fontSize = 13.sp) },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedTextColor = MaruTextStrong,
                                    unfocusedTextColor = MaruTextStrong
                                )
                            )
                        }
                        Surface(
                            onClick = {
                                if (repoInput.isNotBlank()) {
                                    onAddRepo(repoInput)
                                    repoInput = ""
                                }
                            },
                            shape = MaruInputShape,
                            color = MaruAccentPink,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // Repositories Header
        item(key = "repos_header") {
            GlassSectionHeader(
                title = "REPOSITORIES (${savedRepos.size})",
                icon = Icons.Default.Folder,
                color = MaruAccentBlue
            )
        }

        if (savedRepos.isEmpty()) {
            item(key = "repos_empty") {
                GlassCard {
                    Text(
                        text = "No repositories added yet. Type a shortcode like 'megarepo' above to browse its extensions!",
                        color = MaruTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(savedRepos, key = { "repo_${it.url}" }) { repo ->
                val isExpanded = selectedRepoUrl == repo.url
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedRepoUrl = if (isExpanded) null else repo.url },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = repo.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaruTextStrong
                                )
                                Text(
                                    text = "${repo.plugins.size} extensions available • ${if (isExpanded) "Tap to hide" else "Tap to browse"}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = MaruAccentBlue
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { onRemoveRepo(repo) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove Repo", tint = MaruDanger, modifier = Modifier.size(18.dp))
                                }
                                Icon(
                                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = MaruTextMuted
                                )
                            }
                        }

                        AnimatedVisibility(visible = isExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                HorizontalDivider(color = MaruGlassBorderSoft, thickness = 1.dp)

                                repo.plugins.forEach { plugin ->
                                    val isInstalled = installed.contains(plugin.internalName)
                                    Surface(
                                        shape = MaruInputShape,
                                        color = Color(0x33000000),
                                        border = BorderStroke(1.dp, MaruGlassBorderSoft),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = plugin.name,
                                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                                    color = MaruTextStrong
                                                )
                                                if (!plugin.description.isNullOrEmpty()) {
                                                    Text(
                                                        text = plugin.description,
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                                        color = MaruTextMuted,
                                                        maxLines = 1
                                                    )
                                                }
                                                Text(
                                                    text = "v${plugin.version} • ${plugin.language ?: "Multi"}",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                                    color = MaruAccentPink
                                                )
                                            }

                                            if (isInstalled) {
                                                Surface(
                                                    onClick = { onDeletePlugin(plugin.internalName) },
                                                    shape = MaruPillShape,
                                                    color = Color(0x33FF5252),
                                                    border = BorderStroke(1.dp, MaruDanger.copy(alpha = 0.5f))
                                                ) {
                                                    Text(
                                                        text = "UNINSTALL",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                                                        color = MaruDanger,
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                    )
                                                }
                                            } else {
                                                Surface(
                                                    onClick = { onInstallPlugin(plugin) },
                                                    shape = MaruPillShape,
                                                    color = Color(0x334ADE80),
                                                    border = BorderStroke(1.dp, MaruAccentGreen.copy(alpha = 0.5f))
                                                ) {
                                                    Text(
                                                        text = "INSTALL",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                                                        color = MaruAccentGreen,
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "installed_header") {
            GlassSectionHeader(
                title = "INSTALLED EXTENSIONS (${installed.size})",
                icon = Icons.Default.CheckCircle,
                color = MaruAccentGreen
            )
        }

        if (installed.isEmpty()) {
            item(key = "installed_empty") {
                GlassCard {
                    Text(
                        text = "No extensions installed. Tap any repository above to install anime scrapers!",
                        color = MaruTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(installed, key = { "inst_$it" }) { name ->
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Extension, contentDescription = null, tint = MaruAccentBlue)
                            Text(text = name, color = MaruTextStrong, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }

                        IconButton(onClick = { onDeletePlugin(name) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Uninstall", tint = MaruDanger)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StremioTab(
    addons: Set<String>,
    onAddAddon: (String) -> Unit,
    onRemoveAddon: (String) -> Unit
) {
    var addonInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "add_stremio_card") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassSectionHeader(
                        title = "ADD STREMIO ADDON",
                        icon = Icons.Default.Sensors,
                        color = MaruAccentPurple
                    )
                    Text(
                        text = "Add any Stremio Addon manifest URL (e.g. https://torrentio.strem.fun/manifest.json). MAnime streams both HTTP and P2P Torrents internally with libtorrent4j!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaruTextMuted
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = MaruInputShape,
                            color = Color(0x33000000),
                            border = BorderStroke(1.dp, MaruGlassBorderSoft),
                            modifier = Modifier.weight(1f)
                        ) {
                            TextField(
                                value = addonInput,
                                onValueChange = { addonInput = it },
                                placeholder = { Text("https://.../manifest.json", color = MaruTextMuted.copy(alpha = 0.6f), fontSize = 13.sp) },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedTextColor = MaruTextStrong,
                                    unfocusedTextColor = MaruTextStrong
                                )
                            )
                        }
                        Surface(
                            onClick = {
                                if (addonInput.isNotBlank()) {
                                    onAddAddon(addonInput.trim())
                                    addonInput = ""
                                }
                            },
                            shape = MaruInputShape,
                            color = MaruAccentPurple,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }

        item(key = "stremio_addons_header") {
            GlassSectionHeader(
                title = "ACTIVE STREMIO ADDONS (${addons.size})",
                icon = Icons.Default.CloudQueue,
                color = MaruAccentBlue
            )
        }

        items(addons.toList(), key = { "addon_$it" }) { url ->
            val cleanName = remember(url) {
                if (url.contains("torrentio")) "Torrentio (P2P Torrents & Debrid)"
                else if (url.contains("animekitsu")) "Anime Kitsu (Catalog & Streams)"
                else if (url.contains("cinemeta")) "Cinemeta Official"
                else url.substringAfter("://").substringBefore("/")
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = cleanName,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaruTextStrong
                        )
                        Text(
                            text = url,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaruTextMuted,
                            maxLines = 1
                        )
                    }

                    IconButton(onClick = { onRemoveAddon(url) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove Addon", tint = MaruDanger)
                    }
                }
            }
        }
    }
}

@Composable
private fun AniyomiTab() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassSectionHeader(
                    title = "ANIYOMI EXTENSION BRIDGE",
                    icon = Icons.Default.Extension,
                    color = MaruAccentGreen
                )
                Text(
                    text = "MAnime automatically scans and detects all Aniyomi anime extension APKs installed on your device via PackageManager.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaruTextMuted,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
