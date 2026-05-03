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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

val ThemePurple = Color(0xFF9C27B0)
val ThemeBackground = Color(0xFFF0F2F5)

data class Message(val text: String, val isUser: Boolean)

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


//fun buildAdvancedPrompt(userInput: String, currentItems: List<String>): String {
//    val itemsContext = if (currentItems.isNotEmpty()) {
//        currentItems.mapIndexed { index, item -> "${index + 1}. $item" }.joinToString("\n")
//    } else {
//        "없음"
//    }
//
//    return """<|system|>
//You are a JSON API. Output only valid JSON. No other text.
//
//Current items:
//$itemsContext
//
//Output schema:
//{"intent":"chat|buy_product|select_item","query":"string","msg":"string"}
//
//EXAMPLES:
//User: 안녕
//{"intent":"chat","query":"","msg":"안녕하세요! 필요한 물건이 있으면 편하게 말씀해 주세요."}
//
//User: 라면 사줘
//{"intent":"buy_product","query":"라면","msg":"네, 라면 검색을 시작합니다."}
//
//Rules:
//- intent=chat: general conversation, answer in msg (Korean)
//- intent=buy_product: user wants to buy something, set query to product name
//- intent=select_item: user selects a listed item, set query to exact item name
//- msg must always be warm Korean suitable for elderly users
//- Never output anything outside the JSON object<|end|>
//<|user|>
//$userInput<|end|>
//<|assistant|>
//{""".trimIndent()
//}
fun buildAdvancedPrompt(userInput: String, currentItems: List<String>): String {
    val itemsContext = if (currentItems.isNotEmpty()) {
        currentItems.mapIndexed { index, item -> "${index + 1}. $item" }.joinToString("\n")
    } else {
        "없음"
    }

    return """<|start_header_id|>system<|end_header_id|>

You are a JSON API. Output only valid JSON. No other text.

Current items:
$itemsContext

Output schema:
{"intent":"chat|buy_product|select_item","query":"string","msg":"string"}

EXAMPLES:
User: 안녕
{"intent":"chat","query":"","msg":"안녕하세요! 필요한 물건이 있으면 편하게 말씀해 주세요."}

User: 라면 사줘
{"intent":"buy_product","query":"라면","msg":"네, 라면 검색을 시작합니다."}

Rules:
- intent=chat: general conversation, answer in msg (Korean)
- intent=buy_product: user wants to buy something, set query to product name
- intent=select_item: user selects a listed item, set query to exact item name
- msg must always be warm Korean suitable for elderly users
- Never output anything outside the JSON object<|eot_id|><|start_header_id|>user<|end_header_id|>

$userInput<|eot_id|><|start_header_id|>assistant<|end_header_id|>

{""".trimIndent()
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
        val jsonEnd = response.lastIndexOf("}") + 1
        if (jsonStart == -1 || jsonEnd == 0) return null

        val jsonObject = JSONObject(response.substring(jsonStart, jsonEnd))
        AgentCommand(
            intent = jsonObject.optString("intent", ""),
            stage = "",
            systemAction = "",
            query = jsonObject.optString("query", ""),
            confirmationText = jsonObject.optString("msg", "알겠습니다.")
        )
    } catch (e: Exception) {
        Log.e("LlamaParser", "파싱 에러: ${e.message}")
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

    // ✅ STT 상태
    var isListening by remember { mutableStateOf(false) }

    // ✅ 마이크 권한 런처
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "마이크 권한이 필요해요.", Toast.LENGTH_SHORT).show()
        }
    }

    // ✅ SpeechRecognizer 생성 및 해제
    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    DisposableEffect(Unit) {
        onDispose { speechRecognizer.destroy() }
    }

    // ✅ STT 시작 함수
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
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
            }
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
                    inputText = matches[0] // ✅ 인식된 텍스트를 입력창에 자동 삽입
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
                }) { Text("설정하러 가기", color = ThemePurple) }
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ThemeBackground)
            .systemBarsPadding()
            .imePadding()
    ) {
        // 채팅 메시지 목록
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                Box(
                    Modifier.fillMaxWidth(),
                    if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Text(
                        text = msg.text,
                        color = if (msg.isUser) Color.White else Color.Black,
                        modifier = Modifier
                            .background(
                                if (msg.isUser) ThemePurple else Color.White,
                                RoundedCornerShape(16.dp)
                            )
                            .padding(12.dp)
                    )
                }
            }

            if (isLoading) {
                item { Text("생각 중...", modifier = Modifier.padding(12.dp)) }
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
                                    color = ThemePurple,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // 입력 영역
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ✅ 마이크 버튼
            IconButton(
                onClick = {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    startListening()
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (isListening) Color.Red else ThemePurple,
                        CircleShape
                    )
            ) {
                Text(
                    text = if (isListening) "⏹" else "🎤",
                    color = Color.White
                )
            }

            Spacer(Modifier.width(8.dp))

            // 텍스트 입력창
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                enabled = !isLoading,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                placeholder = { Text("말씀하거나 입력해 주세요") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White
                )
            )

            Spacer(Modifier.width(8.dp))

            // 전송 버튼
            IconButton(
                onClick = {
                    if (inputText.isBlank() || isLoading) return@IconButton
                    val text = inputText
                    inputText = ""
                    isLoading = true
                    messages = messages + Message(text, true)

                    CoroutineScope(Dispatchers.Main).launch {
                        val prompt = buildAdvancedPrompt(text, currentProductOptions)
                        val reply = withContext(Dispatchers.IO) { generateResponse(prompt) }
                        val cmd = parseLlmResponse(reply)

                        if (cmd != null && cmd.intent.isNotEmpty()) {
                            when (cmd.intent) {
                                "buy_product", "select_item" -> {
                                    messages = messages + Message(cmd.confirmationText, false)
                                    AgentController.sendCommand(cmd)
                                    currentProductOptions = emptyList()
                                }
                                "chat" -> {
                                    messages = messages + Message(cmd.confirmationText, false)
                                }
                                else -> {
                                    messages = messages + Message("알 수 없는 명령입니다.", false)
                                }
                            }
                        } else {
                            messages = messages + Message("❌ 에러 발생! 모델의 실제 응답:\n$reply", false)
                        }
                        isLoading = false
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(ThemePurple, CircleShape)
            ) {
                Text("↑", color = Color.White)
            }
        }
    }
}