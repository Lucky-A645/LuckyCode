package com.luckycode.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class ChatMsg(
    val id: String,
    val role: String,
    val content: String
)

data class ProviderTemplate(
    val id: String,
    val name: String,
    val baseUrl: String,
    val keyHint: String,
    val isCustom: Boolean = false
)

data class SavedConnector(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<String>
)

val KNOWN_PROVIDERS = listOf(
    ProviderTemplate("auto", "⚡ Auto-Detect (Key Only)", "", "Paste Groq, OpenRouter, Gemini, NVIDIA, xAI, or OpenAI key"),
    ProviderTemplate("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "sk-or-v1-..."),
    ProviderTemplate("groq", "GroqCloud", "https://api.groq.com/openai/v1", "gsk_..."),
    ProviderTemplate("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "sk-..."),
    ProviderTemplate("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "AIzaSy..."),
    ProviderTemplate("openai", "OpenAI", "https://api.openai.com/v1", "sk-proj-..."),
    ProviderTemplate("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "nvapi-..."),
    ProviderTemplate("xai", "xAI (Grok)", "https://api.x.ai/v1", "xai-..."),
    ProviderTemplate("cerebras", "Cerebras", "https://api.cerebras.ai/v1", "csk-..."),
    ProviderTemplate("mistral", "Mistral AI", "https://api.mistral.ai/v1", "Mistral API Key"),
    ProviderTemplate("together", "Together AI", "https://api.together.xyz/v1", "Together API Key"),
    ProviderTemplate("fireworks", "Fireworks AI", "https://api.fireworks.ai/inference/v1", "fw_..."),
    ProviderTemplate("custom", "🌐 Any Custom Website", "", "Enter Base URL + API Key", isCustom = true)
)

fun detectProviderFromKey(key: String): Pair<String, String>? {
    val trimmed = key.trim()
    return when {
        trimmed.startsWith("gsk_") -> "GroqCloud" to "https://api.groq.com/openai/v1"
        trimmed.startsWith("sk-or-") -> "OpenRouter" to "https://openrouter.ai/api/v1"
        trimmed.startsWith("AIza") -> "Google Gemini" to "https://generativelanguage.googleapis.com/v1beta/openai"
        trimmed.startsWith("nvapi-") -> "NVIDIA NIM" to "https://integrate.api.nvidia.com/v1"
        trimmed.startsWith("xai-") -> "xAI (Grok)" to "https://api.x.ai/v1"
        trimmed.startsWith("csk-") -> "Cerebras" to "https://api.cerebras.ai/v1"
        trimmed.startsWith("fw_") -> "Fireworks AI" to "https://api.fireworks.ai/inference/v1"
        trimmed.startsWith("sk-") -> "OpenAI" to "https://api.openai.com/v1"
        else -> null
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF090D16),
                    surface = Color(0xFF111827),
                    primary = Color(0xFF38BDF8),
                    secondary = Color(0xFF10B981)
                )
            ) {
                LuckyCodeWorkspace()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuckyCodeWorkspace() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("luckycode_store", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var connectors by remember { mutableStateOf(loadConnectors(prefs)) }
    var activeConnectorId by remember {
        mutableStateOf(prefs.getString("active_connector_id", connectors.firstOrNull()?.id ?: "") ?: "")
    }
    var activeModel by remember {
        mutableStateOf(prefs.getString("active_model", "Select Model") ?: "Select Model")
    }

    val activeConnector = connectors.find { it.id == activeConnectorId } ?: connectors.firstOrNull()

    var messages by remember {
        mutableStateOf(
            listOf(
                ChatMsg(
                    id = "welcome",
                    role = "assistant",
                    content = "⚡ **Welcome to LuckyCode!**\n\n1. Upar **+ Provider** par tap karein.\n2. Apni **API Key** paste karein (LuckyCode khud provider detect karke saare **Models** fetch kar lega!).\n3. Model select karein aur coding shuru karein."
                )
            )
        )
    }
    var userInput by remember { mutableStateOf("") }
    var isStreaming by remember { mutableStateOf(false) }
    var showProviderSheet by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    val bgDark = Color(0xFF090D16)
    val panelDark = Color(0xFF111827)
    val borderDark = Color(0xFF1F2937)
    val cyanAccent = Color(0xFF38BDF8)
    val greenAccent = Color(0xFF10B981)

    Scaffold(
        containerColor = bgDark,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(panelDark)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // LuckyCode Terminal Branding
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF05070B))
                                .border(1.dp, cyanAccent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = ">_",
                                color = cyanAccent,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "LuckyCode",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = activeConnector?.name ?: "No Provider Connected",
                                color = if (activeConnector != null) greenAccent else Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Action Buttons (+ Provider & Clear)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { showProviderSheet = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = cyanAccent, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Provider", color = cyanAccent, fontSize = 12.sp)
                        }
                        IconButton(onClick = {
                            messages = listOf(messages.first())
                            Toast.makeText(context, "Session Cleared", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear", tint = Color.Gray)
                        }
                    }
                }

                // Model Selector Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D131F))
                        .clickable {
                            if (activeConnector == null) {
                                showProviderSheet = true
                            } else {
                                showModelPicker = true
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (activeConnector != null) greenAccent else Color.Red)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (activeConnector != null) "MODEL: $activeModel" else "Tap here to add API Key & fetch models",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Switch Model",
                        tint = cyanAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
                HorizontalDivider(color = borderDark, thickness = 1.dp)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { visible = true }

                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(tween(220)) + slideInVertically(
                            initialOffsetY = { it / 4 },
                            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)
                        )
                    ) {
                        LuckyCodeMessageItem(msg = msg, context = context)
                    }
                }

                if (isStreaming) {
                    item {
                        StreamingBadge(cyanAccent)
                    }
                }
            }

            // Bottom Command Input Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(panelDark)
            ) {
                HorizontalDivider(color = borderDark, thickness = 1.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = userInput,
                        onValueChange = { userInput = it },
                        placeholder = {
                            Text(
                                "Ask LuckyCode anything...",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 5,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = cyanAccent,
                            unfocusedBorderColor = borderDark,
                            focusedContainerColor = bgDark,
                            unfocusedContainerColor = bgDark
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FloatingActionButton(
                        onClick = {
                            if (userInput.isBlank() || isStreaming) return@FloatingActionButton
                            if (activeConnector == null) {
                                showProviderSheet = true
                                Toast.makeText(context, "Connect an API Key first!", Toast.LENGTH_SHORT).show()
                                return@FloatingActionButton
                            }

                            val prompt = userInput.trim()
                            userInput = ""
                            val userMsg = ChatMsg(
                                id = System.currentTimeMillis().toString(),
                                role = "user",
                                content = prompt
                            )
                            val aiMsgId = (System.currentTimeMillis() + 1).toString()
                            val history = messages.filter { it.id != "welcome" } + userMsg

                            messages = messages + userMsg + ChatMsg(aiMsgId, "assistant", "")
                            isStreaming = true

                            scope.launch {
                                listState.animateScrollToItem(messages.size - 1)
                                streamOpenAICompatible(
                                    baseUrl = activeConnector.baseUrl,
                                    apiKey = activeConnector.apiKey,
                                    model = activeModel,
                                    history = history,
                                    onChunk = { delta ->
                                        messages = messages.map {
                                            if (it.id == aiMsgId) it.copy(content = it.content + delta) else it
                                        }
                                    },
                                    onFinish = {
                                        isStreaming = false
                                    }
                                )
                            }
                        },
                        containerColor = if (isStreaming) Color.DarkGray else cyanAccent,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.Black)
                    }
                }
            }
        }

        // 1. Add / Manage Provider Modal (Only API Key Needed + Auto Fetch Models)
        if (showProviderSheet) {
            ProviderManagerDialog(
                savedConnectors = connectors,
                activeConnectorId = activeConnector?.id ?: "",
                onSelectConnector = { conn ->
                    activeConnectorId = conn.id
                    activeModel = conn.models.firstOrNull() ?: "gpt-4o-mini"
                    prefs.edit()
                        .putString("active_connector_id", activeConnectorId)
                        .putString("active_model", activeModel)
                        .apply()
                    showProviderSheet = false
                    showModelPicker = true
                },
                onDeleteConnector = { connId ->
                    val updated = connectors.filterNot { it.id == connId }
                    connectors = updated
                    saveConnectors(prefs, updated)
                    if (activeConnectorId == connId) {
                        activeConnectorId = updated.firstOrNull()?.id ?: ""
                        activeModel = updated.firstOrNull()?.models?.firstOrNull() ?: "Select Model"
                    }
                },
                onConnectorAdded = { newConn ->
                    val updated = connectors.filterNot { it.name == newConn.name && it.baseUrl == newConn.baseUrl } + newConn
                    connectors = updated
                    saveConnectors(prefs, updated)
                    activeConnectorId = newConn.id
                    activeModel = newConn.models.firstOrNull() ?: "default"
                    prefs.edit()
                        .putString("active_connector_id", activeConnectorId)
                        .putString("active_model", activeModel)
                        .apply()
                    showProviderSheet = false
                    showModelPicker = true
                    Toast.makeText(
                        context,
                        "✅ ${newConn.name} Connected (${newConn.models.size} models loaded!)",
                        Toast.LENGTH_LONG
                    ).show()
                },
                onDismiss = { showProviderSheet = false }
            )
        }

        // 2. Searchable Model Picker Modal (All Auto-Fetched Models)
        if (showModelPicker && activeConnector != null) {
            ModelPickerDialog(
                connector = activeConnector,
                currentModel = activeModel,
                onSelectModel = { chosen ->
                    activeModel = chosen
                    prefs.edit().putString("active_model", chosen).apply()
                    showModelPicker = false
                },
                onRefreshModels = {
                    scope.launch {
                        val refreshed = fetchModelsFromEndpoint(activeConnector.baseUrl, activeConnector.apiKey)
                        if (refreshed.isNotEmpty()) {
                            val updatedConn = activeConnector.copy(models = refreshed)
                            val updatedList = connectors.map { if (it.id == activeConnector.id) updatedConn else it }
                            connectors = updatedList
                            saveConnectors(prefs, updatedList)
                            Toast.makeText(context, "Refreshed ${refreshed.size} models!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Could not auto-fetch models", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDismiss = { showModelPicker = false }
            )
        }
    }
}

@Composable
fun LuckyCodeMessageItem(msg: ChatMsg, context: Context) {
    val isUser = msg.role == "user"
    val bgCard = if (isUser) Color(0xFF1E293B) else Color(0xFF111827)
    val borderCol = if (isUser) Color(0xFF38BDF8).copy(alpha = 0.4f) else Color(0xFF1F2937)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgCard)
            .border(1.dp, borderCol, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        // Role Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isUser) "YOU" else ">_ LUCKYCODE",
                color = if (isUser) Color(0xFF38BDF8) else Color(0xFF10B981),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            if (msg.content.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy Full Message",
                    tint = Color.Gray,
                    modifier = Modifier
                        .size(15.dp)
                        .clickable {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("Message", msg.content))
                            Toast.makeText(context, "Copied response", Toast.LENGTH_SHORT).show()
                        }
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))

        // Render Markdown Code Blocks cleanly
        val segments = remember(msg.content) { msg.content.split("```") }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                segments.forEachIndexed { index, part ->
                    if (index % 2 == 1) {
                        // Code Block Segment
                        val lines = part.lines()
                        val lang = lines.firstOrNull()?.trim() ?: "code"
                        val codeBody = if (lines.size > 1) lines.drop(1).joinToString("\n") else part

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF05070B))
                                .border(1.dp, Color(0xFF30363D), RoundedCornerShape(10.dp))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF161B22))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = lang.ifEmpty { "code" },
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable {
                                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cb.setPrimaryClip(ClipData.newPlainText("Code", codeBody))
                                        Toast.makeText(context, "Code Copied!", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy Code",
                                        tint = Color.LightGray,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy", color = Color.LightGray, fontSize = 11.sp)
                                }
                            }
                            Text(
                                text = codeBody.trim(),
                                color = Color(0xFFE6EDF3),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(10.dp)
                            )
                        }
                    } else if (part.isNotBlank()) {
                        Text(
                            text = part.trim(),
                            color = Color(0xFFE5E7EB),
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProviderManagerDialog(
    savedConnectors: List<SavedConnector>,
    activeConnectorId: String,
    onSelectConnector: (SavedConnector) -> Unit,
    onDeleteConnector: (String) -> Unit,
    onConnectorAdded: (SavedConnector) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTemplate by remember { mutableStateOf(KNOWN_PROVIDERS.first()) }
    var apiKeyInput by remember { mutableStateOf("") }
    var customNameInput by remember { mutableStateOf("") }
    var customUrlInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var isFetching by remember { mutableStateOf(false) }

    val autoDetected = remember(apiKeyInput) { detectProviderFromKey(apiKeyInput) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111827),
        title = {
            Text("🔌 Add or Switch Provider", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (savedConnectors.isNotEmpty()) {
                    Text("Connected Providers:", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    savedConnectors.forEach { conn ->
                        val isSelected = conn.id == activeConnectorId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) Color(0xFF1E293B) else Color(0xFF090D16))
                                .border(
                                    1.dp,
                                    if (isSelected) Color(0xFF10B981) else Color(0xFF1F2937),
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { onSelectConnector(conn) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(conn.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("${conn.models.size} models available", color = Color(0xFF10B981), fontSize = 11.sp)
                            }
                            IconButton(
                                onClick = { onDeleteConnector(conn.id) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.Gray)
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFF1F2937))
                }

                Text("Select Platform (or Paste Key for Auto-Detect):", color = Color.LightGray, fontSize = 12.sp)

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    KNOWN_PROVIDERS.forEach { provider ->
                        val chosen = selectedTemplate.id == provider.id
                        FilterChip(
                            selected = chosen,
                            onClick = { selectedTemplate = provider },
                            label = { Text(provider.name, fontSize = 11.sp) }
                        )
                    }
                }

                if (selectedTemplate.id == "auto" && autoDetected != null) {
                    Text(
                        text = "✨ Auto-Detected: ${autoDetected.first}",
                        color = Color(0xFF10B981),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (selectedTemplate.isCustom) {
                    OutlinedTextField(
                        value = customNameInput,
                        onValueChange = { customNameInput = it },
                        label = { Text("Provider Name (e.g. My Server)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = customUrlInput,
                        onValueChange = { customUrlInput = it },
                        label = { Text("Base URL (e.g. [https://api.example.com/v1](https://api.example.com/v1))") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("Paste API Key Only") },
                    placeholder = { Text(selectedTemplate.keyHint, color = Color.Gray, fontSize = 12.sp) },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = Color.Gray
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                if (isFetching) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connecting & fetching models...", color = Color(0xFF38BDF8), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isFetching && apiKeyInput.isNotBlank(),
                onClick = {
                    val cleanKey = apiKeyInput.trim()
                    val resolvedName: String
                    val resolvedUrl: String

                    when {
                        selectedTemplate.isCustom -> {
                            resolvedName = customNameInput.ifBlank { "Custom API" }.trim()
                            resolvedUrl = customUrlInput.trim().removeSuffix("/")
                            if (resolvedUrl.isBlank()) {
                                Toast.makeText(context, "Enter Base URL for custom provider", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                        }
                        selectedTemplate.id == "auto" -> {
                            val detected = detectProviderFromKey(cleanKey)
                            if (detected != null) {
                                resolvedName = detected.first
                                resolvedUrl = detected.second
                            } else {
                                Toast.makeText(
                                    context,
                                    "Key prefix not recognized! Pick platform chip above or choose Custom Website.",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@Button
                            }
                        }
                        else -> {
                            resolvedName = selectedTemplate.name
                            resolvedUrl = selectedTemplate.baseUrl
                        }
                    }

                    isFetching = true
                    scope.launch {
                        val fetchedModels = fetchModelsFromEndpoint(resolvedUrl, cleanKey)
                        isFetching = false
                        val finalModels = if (fetchedModels.isNotEmpty()) {
                            fetchedModels
                        } else {
                            listOf("gpt-4o-mini", "deepseek-chat", "llama-3.3-70b-versatile")
                        }

                        val newConn = SavedConnector(
                            id = System.currentTimeMillis().toString(),
                            name = resolvedName,
                            baseUrl = resolvedUrl,
                            apiKey = cleanKey,
                            models = finalModels
                        )
                        onConnectorAdded(newConn)
                    }
                }
            ) {
                Text(if (isFetching) "Fetching..." else "Connect & Load Models")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color.Gray)
            }
        }
    )
}

@Composable
fun ModelPickerDialog(
    connector: SavedConnector,
    currentModel: String,
    onSelectModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var customModelTyped by remember { mutableStateOf("") }

    val filteredModels = remember(connector.models, searchQuery) {
        if (searchQuery.isBlank()) connector.models
        else connector.models.filter { it.contains(searchQuery.trim(), ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111827),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Select Model", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("${connector.name} (${connector.models.size} models)", color = Color(0xFF10B981), fontSize = 11.sp)
                }
                IconButton(onClick = onRefreshModels) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Models", tint = Color(0xFF38BDF8))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search ${connector.models.size} models...", color = Color.Gray, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredModels) { modelId ->
                        val isSelected = modelId == currentModel
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF1E293B) else Color(0xFF090D16))
                                .border(
                                    1.dp,
                                    if (isSelected) Color(0xFF38BDF8) else Color(0xFF1F2937),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onSelectModel(modelId) }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = modelId,
                                color = if (isSelected) Color(0xFF38BDF8) else Color.White,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = customModelTyped,
                        onValueChange = { customModelTyped = it },
                        placeholder = { Text("Or type custom model ID...", color = Color.Gray, fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    if (customModelTyped.isNotBlank()) {
                        Button(onClick = { onSelectModel(customModelTyped.trim()) }) {
                            Text("Use")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = Color(0xFF38BDF8))
            }
        }
    )
}

@Composable
fun StreamingBadge(accentColor: Color) {
    val transition = rememberInfiniteTransition(label = "stream")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "pulse"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = alpha))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "LuckyCode is streaming...",
            color = accentColor.copy(alpha = alpha),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

suspend fun fetchModelsFromEndpoint(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val endpoint = "${baseUrl.trimEnd('/')}/models"
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val bodyStr = response.body?.string() ?: return@withContext emptyList()
            val root = JSONObject(bodyStr)
            val dataArr = root.optJSONArray("data") ?: return@withContext emptyList()

            val result = mutableListOf<String>()
            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i)
                val id = item?.optString("id", "") ?: ""
                if (id.isNotBlank()) {
                    result.add(id.removePrefix("models/"))
                }
            }
            result.sorted()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

suspend fun streamOpenAICompatible(
    baseUrl: String,
    apiKey: String,
    model: String,
    history: List<ChatMsg>,
    onChunk: (String) -> Unit,
    onFinish: () -> Unit
) = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        val messagesArray = JSONArray()
        messagesArray.put(
            JSONObject()
                .put("role", "system")
                .put("content", "You are LuckyCode AI, an elite software engineering and coding assistant.")
        )
        history.takeLast(14).forEach { msg ->
            messagesArray.put(JSONObject().put("role", msg.role).put("content", msg.content))
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("stream", true)
        }

        val endpoint = "${baseUrl.trimEnd('/')}/chat/completions"
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: "HTTP ${response.code}"
                withContext(Dispatchers.Main) {
                    onChunk("⚠️ **API Error (${response.code}):**\n```json\n$err\n```")
                }
                return@withContext
            }

            val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val raw = line!!.trim()
                if (!raw.startsWith("data:")) continue
                val dataStr = raw.removePrefix("data:").trim()
                if (dataStr == "[DONE]") break

                try {
                    val json = JSONObject(dataStr)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val delta = choices.getJSONObject(0).optJSONObject("delta")
                        val content = delta?.optString("content", "") ?: ""
                        if (content.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                onChunk(content)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            onChunk("\n⚠️ **Network Error:** ${e.localizedMessage}")
        }
    } finally {
        withContext(Dispatchers.Main) {
            onFinish()
        }
    }
}

fun saveConnectors(prefs: android.content.SharedPreferences, list: List<SavedConnector>) {
    val arr = JSONArray()
    list.forEach { conn ->
        val modelsArr = JSONArray()
        conn.models.forEach { modelsArr.put(it) }
        val obj = JSONObject().apply {
            put("id", conn.id)
            put("name", conn.name)
            put("baseUrl", conn.baseUrl)
            put("apiKey", conn.apiKey)
            put("models", modelsArr)
        }
        arr.put(obj)
    }
    prefs.edit().putString("saved_connectors_json", arr.toString()).apply()
}

fun loadConnectors(prefs: android.content.SharedPreferences): List<SavedConnector> {
    val raw = prefs.getString("saved_connectors_json", null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        val list = mutableListOf<SavedConnector>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val mArr = obj.optJSONArray("models") ?: JSONArray()
            val models = mutableListOf<String>()
            for (j in 0 until mArr.length()) {
                models.add(mArr.getString(j))
            }
            list.add(
                SavedConnector(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    baseUrl = obj.getString("baseUrl"),
                    apiKey = obj.getString("apiKey"),
                    models = models
                )
            )
        }
        list
    } catch (_: Exception) {
        emptyList()
    }
}
