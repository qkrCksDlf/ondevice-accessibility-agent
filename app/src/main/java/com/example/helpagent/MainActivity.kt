package com.example.helpagent

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

// 🌟 모드별 색상 팔레트
val ThemePurple = Color(0xFF9C27B0)
val PrivacyBackground = Color(0xFFF3E5F5)
val SpeedOrange = Color(0xFFFF9800)
val SpeedBackground = Color(0xFFFFF3E0)
val BubbleBotBg = Color.White
val TextDark = Color(0xFF222222)
val TextGray = Color(0xFF999999)

const val SPEED_SERVER_URL = "http://192.168.0.5:8000/chat"

data class Message(val text: String, val isUser: Boolean)

enum class AgentMode(val label: String, val displayName: String) {
    PRIVACY("🔒", "안전 모드"),
    SPEED("⚡", "빠른 모드")
}

private val httpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

class MainActivity : ComponentActivity() {

    external fun loadLlamaModel(modelPath: String): Boolean
    external fun generateLlamaResponse(prompt: String): String
    external fun releaseLlamaModel()

    companion object {
        private const val MODEL_ASSET_PATH = "models/llama-3.2-3b-instruct-q4_k_m.gguf"
    }

    init { System.loadLibrary("native-lib") }

    private fun copyAssetToInternalStorage(assetName: String, subDir: String = "models"): String {
        val outDir = File(filesDir, subDir)
        if (!outDir.exists()) outDir.mkdirs()
        val outFile = File(outDir, assetName.substringAfterLast('/'))
        if (!outFile.exists()) {
            assets.open(assetName).use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
        }
        return outFile.absolutePath
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            MainScreen(
                loadModel = { loadLlamaModel(copyAssetToInternalStorage(MODEL_ASSET_PATH)) },
                generateResponse = { prompt -> generateLlamaResponse(prompt) }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { releaseLlamaModel() } catch (e: Exception) { }
    }
}

// ===============================================
// LLM 프롬프트 (온디바이스용)
// ===============================================
fun buildAdvancedPrompt(
    userInput: String,
    currentItems: List<String>
): String {
    val itemsContext = if (currentItems.isNotEmpty()) {
        currentItems.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
    } else "없음"

    return """<|start_header_id|>system<|end_header_id|>
JSON만 출력하는 분류기.

intent: chat / buy_product / book_ktx / select_item

쿠팡목록: $itemsContext

형식: {"intent":"...","query":"...","msg":"한국어"}

예시:
입력: 안녕
{"intent":"chat","query":"","msg":"안녕하세요!"}

입력: 라면 사줘
{"intent":"buy_product","query":"라면","msg":"네, 라면을 찾아드릴게요."}

입력: 기차 예매해줘
{"intent":"book_ktx","query":"","msg":"네, 기차표 예매 도와드릴게요."}

입력: KTX 타고 싶어
{"intent":"book_ktx","query":"","msg":"네, 기차표 예매 도와드릴게요."}

입력: 휴지 사줘
{"intent":"buy_product","query":"휴지","msg":"네, 휴지를 찾아드릴게요."}<|eot_id|><|start_header_id|>user<|end_header_id|>
$userInput<|eot_id|><|start_header_id|>assistant<|end_header_id|>
""".trimIndent()
}

fun isAutoBuyRequest(input: String): Boolean {
    val autoKeywords = listOf("아무거나", "아무꺼나", "알아서", "괜찮은", "추천", "골라줘", "적당한", "아무")
    return autoKeywords.any { input.contains(it) }
}

fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
    val expectedComponentName = ComponentName(context, serviceClass).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabledServices)
    while (splitter.hasNext()) {
        if (splitter.next().equals(expectedComponentName, ignoreCase = true)) return true
    }
    return false
}

fun parseLlmResponse(response: String): AgentCommand? {
    return try {
        Log.d("LlamaRaw", "원본 출력: $response")
        val jsonStart = response.indexOf("{")
        if (jsonStart == -1) return null

        var jsonStr = response.substring(jsonStart)

        val openCount = jsonStr.count { it == '{' }
        val closeCount = jsonStr.count { it == '}' }
        if (openCount > closeCount) {
            jsonStr = jsonStr.trimEnd().trimEnd(',').trimEnd()
            if (jsonStr.count { it == '"' } % 2 != 0) jsonStr += "\""
            repeat(openCount - closeCount) { jsonStr += "}" }
        } else if (closeCount > openCount) {
            val jsonEnd = jsonStr.lastIndexOf("}") + 1
            jsonStr = jsonStr.substring(0, jsonEnd)
        }

        val jsonObject = JSONObject(jsonStr)

        AgentCommand(
            intent = jsonObject.optString("intent", ""),
            query = jsonObject.optString("query", ""),
            confirmationText = jsonObject.optString("msg", "알겠습니다.")
        )
    } catch (e: Exception) {
        Log.e("LlamaParser", "파싱 에러: ${e.message}")
        null
    }
}

fun callSpeedServer(userMessage: String): AgentCommand? {
    return try {
        val json = JSONObject().put("message", userMessage).toString()
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(SPEED_SERVER_URL)
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bodyStr = resp.body?.string() ?: return null
            val obj = JSONObject(bodyStr)
            AgentCommand(
                intent = obj.optString("intent", ""),
                query = obj.optString("query", ""),
                confirmationText = obj.optString("msg", "")
            )
        }
    } catch (e: Exception) {
        Log.e("SpeedMode", "서버 호출 실패", e)
        null
    }
}

@Composable
fun MainScreen(loadModel: () -> Boolean, generateResponse: (String) -> String) {
    var modelState by remember { mutableStateOf<ModelState>(ModelState.Loading) }
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { loadModel() }
        modelState = if (result) ModelState.Ready else ModelState.Failed("모델 로딩 실패")
    }
    when (val state = modelState) {
        is ModelState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator()
        }
        is ModelState.Failed -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(state.message, color = Color.Red)
        }
        is ModelState.Ready -> ChatScreen(generateResponse)
    }
}

sealed class ModelState {
    object Loading : ModelState()
    object Ready : ModelState()
    data class Failed(val message: String) : ModelState()
}

// 🌟 햄버거 메뉴 버튼
@Composable
fun MenuButton(
    accentColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .shadow(4.dp, CircleShape)
            .size(46.dp)
            .background(Color.White, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Menu,
            contentDescription = "메뉴",
            tint = accentColor,
            modifier = Modifier.size(24.dp)
        )
    }
}

// 🌟 가로로 긴 토글 스위치 (CloudLock / Bolt 아이콘)
@Composable
fun BigModeToggle(
    currentMode: AgentMode,
    accentColor: Color,
    onToggle: () -> Unit
) {
    val isSpeed = currentMode == AgentMode.SPEED

    val trackWidth = 240.dp
    val trackHeight = 46.dp
    val thumbWidth = 116.dp
    val thumbPadding = 4.dp

    val thumbOffset by animateDpAsState(
        targetValue = if (isSpeed) trackWidth - thumbWidth - thumbPadding else thumbPadding,
        animationSpec = tween(300),
        label = "thumbOffset"
    )

    Box(
        modifier = Modifier
            .shadow(6.dp, shape = RoundedCornerShape(50))
            .size(width = trackWidth, height = trackHeight)
            .background(accentColor, shape = RoundedCornerShape(50))
            .clickable { onToggle() },
        contentAlignment = Alignment.CenterStart
    ) {
        // 트랙 위 양쪽 라벨 (선택 안 된 쪽만 흐릿하게 보임)
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 왼쪽: 안전 모드
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                if (isSpeed) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "안전 모드",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }
            }
            // 오른쪽: 빠른 모드
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                if (!isSpeed) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "빠른 모드",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }

        // thumb (선택된 모드 표시)
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(width = thumbWidth, height = trackHeight - thumbPadding * 2)
                .shadow(3.dp, RoundedCornerShape(50))
                .background(Color.White, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (isSpeed) Icons.Filled.Bolt else Icons.Filled.Security,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (isSpeed) "빠른 모드" else "안전 모드",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
            }
        }
    }
}

@Composable
fun ChatScreen(generateResponse: (String) -> String) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context, AutoAgentService::class.java))
    }

    var messages by remember { mutableStateOf(listOf(Message("안녕하세요! 무엇을 도와드릴까요?", false))) }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var currentProductOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var isListening by remember { mutableStateOf(false) }

    var currentMode by remember { mutableStateOf(AgentMode.PRIVACY) }

    val accentColor by animateColorAsState(
        targetValue = if (currentMode == AgentMode.SPEED) SpeedOrange else ThemePurple,
        animationSpec = tween(400),
        label = "accentColor"
    )
    val bgColor by animateColorAsState(
        targetValue = if (currentMode == AgentMode.SPEED) SpeedBackground else PrivacyBackground,
        animationSpec = tween(400),
        label = "bgColor"
    )

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "마이크 권한이 필요해요.", Toast.LENGTH_SHORT).show()
        }
    }

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    DisposableEffect(Unit) {
        onDispose { speechRecognizer.destroy() }
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Toast.makeText(context, "이 기기에서는 음성 인식을 사용할 수 없어요.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isListening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) {
                isListening = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "오디오 오류"
                    SpeechRecognizer.ERROR_NO_MATCH -> "인식 실패, 다시 말씀해 주세요."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성이 감지되지 않았어요."
                    SpeechRecognizer.ERROR_NETWORK -> "네트워크 오류"
                    else -> "음성 인식 오류"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    inputText = matches[0]
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer.startListening(intent)
    }

    LaunchedEffect(context) {
        hasPermission = isAccessibilityServiceEnabled(context, AutoAgentService::class.java)
    }

    // 🌟 자동 스크롤: 새 메시지/로딩 상태 변경 시 마지막으로
    LaunchedEffect(messages.size, isLoading, currentProductOptions.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(Unit) {
        AgentController.resultFlow.collect { list ->
            if (list.isNotEmpty()) {
                currentProductOptions = list
                val resultMsg = "다음 상품을 찾았습니다.\n\n" +
                        list.mapIndexed { index, s -> "${index + 1}. $s" }.joinToString("\n") +
                        "\n\n질문하시거나 원하는 상품을 말씀해 주세요!"
                messages = messages + Message(resultMsg, false)
            }
        }
    }

    if (!hasPermission) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("접근성 권한 필요", fontWeight = FontWeight.Bold) },
            text = { Text("앱 제어를 위해 '설정'에서 접근성 권한을 켜주세요.") },
            confirmButton = {
                TextButton(onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }) { Text("설정하러 가기", color = accentColor) }
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(bgColor)
            .systemBarsPadding()
            .imePadding()
    ) {
        // 🌟 상단: [메뉴] + [토글]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MenuButton(
                accentColor = accentColor,
                onClick = {
                    Toast.makeText(context, "메뉴 (준비 중)", Toast.LENGTH_SHORT).show()
                }
            )

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd
            ) {
                BigModeToggle(
                    currentMode = currentMode,
                    accentColor = accentColor,
                    onToggle = {
                        currentMode = if (currentMode == AgentMode.PRIVACY)
                            AgentMode.SPEED else AgentMode.PRIVACY
                        val msg = when (currentMode) {
                            AgentMode.PRIVACY -> "🔒 안전 모드로 바꿨어요. 인터넷 없이 동작해요."
                            AgentMode.SPEED -> "⚡ 빠른 모드로 바꿨어요. 날씨 같은 정보도 알 수 있어요."
                        }
                        messages = messages + Message(msg, false)
                    }
                )
            }
        }

        // 채팅 메시지 영역
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { msg ->
                Box(
                    Modifier.fillMaxWidth(),
                    if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Text(
                        text = msg.text,
                        color = if (msg.isUser) Color.White else TextDark,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .background(
                                if (msg.isUser) accentColor else BubbleBotBg,
                                RoundedCornerShape(20.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            if (isLoading) {
                item {
                    Text(
                        "🤔 생각 중...",
                        fontSize = 15.sp,
                        color = TextGray,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (currentProductOptions.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        currentProductOptions.forEach { product ->
                            Button(
                                onClick = {
                                    messages = messages + Message("👉 $product 선택함", true)
                                    messages = messages + Message("알겠습니다! 결제 준비를 시작합니다.", false)
                                    val buyCommand = AgentCommand(
                                        intent = "select_item",
                                        stage = "buy",
                                        systemAction = "click",
                                        query = product,
                                        confirmationText = ""
                                    )
                                    AgentController.sendCommand(buyCommand)
                                    currentProductOptions = emptyList()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                            ) {
                                Text(
                                    text = product,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = accentColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // 마지막에 약간 여백
            item { Spacer(Modifier.height(4.dp)) }
        }

        // 🌟 입력창 영역 (Mic / ArrowUpward 아이콘)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .shadow(2.dp, RoundedCornerShape(20.dp))
                    .background(Color.White, RoundedCornerShape(20.dp))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 🎤 마이크 버튼
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            if (isListening) Color.Red else accentColor,
                            CircleShape
                        )
                        .clickable {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            startListening()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (isListening) "정지" else "음성 입력",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(Modifier.width(10.dp))

                BasicTextFieldWrapper(
                    value = inputText,
                    onValueChange = { inputText = it },
                    enabled = !isLoading,
                    placeholder = "말씀하거나 입력해 주세요",
                    modifier = Modifier.weight(1f)
                )

                Spacer(Modifier.width(10.dp))

                // ↑ 전송 버튼
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(accentColor, CircleShape)
                        .clickable {
                            if (inputText.isBlank() || isLoading) return@clickable
                            val text = inputText
                            inputText = ""
                            isLoading = true
                            messages = messages + Message(text, true)

                            CoroutineScope(Dispatchers.Main).launch {
                                val cmd: AgentCommand? = if (currentMode == AgentMode.SPEED) {
                                    withContext(Dispatchers.IO) { callSpeedServer(text) }
                                } else {
                                    val prompt = buildAdvancedPrompt(text, currentProductOptions)
                                    val reply = withContext(Dispatchers.IO) { generateResponse(prompt) }
                                    parseLlmResponse(reply)
                                }

                                if (cmd != null && cmd.intent.isNotEmpty()) {
                                    when (cmd.intent) {
                                        "buy_product", "select_item" -> {
                                            val isAuto = isAutoBuyRequest(text)
                                            val finalCmd = cmd.copy(autoMode = isAuto)
                                            val msgText = if (isAuto) {
                                                "${cmd.confirmationText}\n\n알아서 골라드릴게요. 결제 직전에 한 번 확인 받을게요."
                                            } else {
                                                cmd.confirmationText
                                            }
                                            messages = messages + Message(msgText, false)
                                            AgentController.sendCommand(finalCmd)
                                            currentProductOptions = emptyList()
                                        }
                                        "book_ktx" -> {
                                            messages = messages + Message(cmd.confirmationText, false)
                                            messages = messages + Message("코레일톡을 켜고 진행할게요.", false)
                                            AgentController.sendCommand(
                                                AgentCommand(
                                                    intent = "book_ktx",
                                                    stage = "launch",
                                                    query = ""
                                                )
                                            )
                                        }
                                        "chat" -> {
                                            messages = messages + Message(cmd.confirmationText, false)
                                        }
                                        else -> {
                                            messages = messages + Message("알 수 없는 명령입니다.", false)
                                        }
                                    }
                                } else {
                                    val errorMsg = if (currentMode == AgentMode.SPEED) {
                                        "⚠️ 서버에 연결할 수 없어요.\n안전 모드로 바꿔서 다시 시도해 주세요."
                                    } else {
                                        "⚠️ 응답을 이해하지 못했어요. 다시 말씀해 주세요."
                                    }
                                    messages = messages + Message(errorMsg, false)
                                }
                                isLoading = false
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowUpward,
                        contentDescription = "전송",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun BasicTextFieldWrapper(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        textStyle = TextStyle(
            color = TextDark,
            fontSize = 16.sp
        ),
        modifier = modifier,
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = TextGray,
                        fontSize = 16.sp
                    )
                }
                innerTextField()
            }
        }
    )
}