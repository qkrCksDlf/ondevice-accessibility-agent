package com.example.helpagent

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CompletableDeferred
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

// 🌟 길찾기 카드 색상
val WalkGray = Color(0xFF5F5E5A)
val SubwayBlue = Color(0xFF185FA5)
val BusGreen = Color(0xFF3B6D11)
val ArriveOrange = Color(0xFFD85A30)
val CardLineColor = Color(0xFFE0E0E0)
val ChipBg = Color(0xFFF0F0F0)

const val SPEED_SERVER_URL = "http://10.136.95.1:8000/chat"

// 🌟 길찾기 한 구간 (지하철/버스/도보)
data class TransitStep(
    val mode: String,            // "subway" / "bus" / "walk"
    val line: String = "",       // 노선명 (예: "수도권 1호선", "721번")
    val from: String = "",
    val to: String = "",
    val stations: Int = 0,
    val timeMin: Int? = null
)

// 🌟 길찾기 한 경로
data class TransitRoute(
    val from: String,
    val to: String,
    val totalTimeMin: Int?,
    val transferCount: Int,
    val walkingTimeMin: Int?,
    val fare: Int?,
    val steps: List<TransitStep>
)

data class Message(
    val text: String,
    val isUser: Boolean,
    val route: TransitRoute? = null   // 🌟 null 이 아니면 말풍선 대신 길찾기 카드로 렌더
)

enum class AgentMode(val label: String, val displayName: String) {
    PRIVACY("🔒", "안전 모드"),
    SPEED("⚡", "빠른 모드")
}

private val httpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

// 🌟 서버 응답을 담는 결과 (명령 + 선택적 길찾기 경로)
data class SpeedResult(
    val command: AgentCommand,
    val route: TransitRoute? = null
)

// ===============================================
// 🌟 드로어(사이드 메뉴) 화면 구분
// ===============================================
enum class DrawerScreen { MENU, COUPANG_GUIDE, KORAIL_GUIDE }

// 🌟 데모용 채팅 기록 항목
data class DemoChatHistory(
    val title: String,
    val date: String
)

val DEMO_CHAT_HISTORY = listOf(
    DemoChatHistory("라면 주문하기", "오늘"),
    DemoChatHistory("서울 → 대전 기차표 예매", "어제"),
    DemoChatHistory("인천시청에서 부평역 가는 길", "어제"),
    DemoChatHistory("내일 날씨 물어보기", "6월 8일"),
    DemoChatHistory("휴지 주문하기", "6월 5일")
)

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
    userInput: String
): String {
    return """<|start_header_id|>system<|end_header_id|>
JSON만 출력하는 분류기.

intent: chat / buy_product / book_ktx
- buy_product: 물건 사기/주문/필요/떨어짐
- book_ktx: 기차/KTX 예매
- chat: 그 외 모든 대화. 물건·기차를 언급만 하면 chat.

형식: {"intent":"...","query":"...","msg":"한국어"}
query는 buy_product 일 때만 물건명, 나머지는 "".

예시:
입력: 안녕
{"intent":"chat","query":"","msg":"안녕하세요! 무엇을 도와드릴까요?"}

입력: 라면 맛있겠다
{"intent":"chat","query":"","msg":"라면 맛있죠! 드시고 싶으면 주문도 도와드려요."}

입력: 라면 사줘
{"intent":"buy_product","query":"라면","msg":"네, 라면을 찾아드릴게요."}

입력: 세제 떨어졌어
{"intent":"buy_product","query":"세제","msg":"네, 세제를 찾아드릴게요."}

입력: 기차 예매해줘
{"intent":"book_ktx","query":"","msg":"네, 기차표 예매 도와드릴게요."}

입력: KTX 타고 싶어
{"intent":"book_ktx","query":"","msg":"네, 기차표 예매 도와드릴게요."}<|eot_id|><|start_header_id|>user<|end_header_id|>
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

// 🌟 서버 응답에서 route(JSON) → TransitRoute 로 파싱
fun parseTransitRoute(obj: JSONObject): TransitRoute? {
    return try {
        val routesArr = obj.optJSONArray("routes") ?: return null
        if (routesArr.length() == 0) return null

        // 가장 빠른 경로(첫 번째)만 사용
        val best = routesArr.getJSONObject(0)
        val stepsArr = best.optJSONArray("steps")
        val steps = mutableListOf<TransitStep>()
        if (stepsArr != null) {
            for (i in 0 until stepsArr.length()) {
                val s = stepsArr.getJSONObject(i)
                steps.add(
                    TransitStep(
                        mode = s.optString("mode", ""),
                        line = s.optString("line", ""),
                        from = s.optString("from", ""),
                        to = s.optString("to", ""),
                        stations = s.optInt("stations", 0),
                        timeMin = if (s.has("time_min") && !s.isNull("time_min"))
                            s.optInt("time_min") else null
                    )
                )
            }
        }

        TransitRoute(
            from = obj.optString("from", ""),
            to = obj.optString("to", ""),
            totalTimeMin = if (best.has("total_time_min") && !best.isNull("total_time_min"))
                best.optInt("total_time_min") else null,
            transferCount = best.optInt("transfer_count", 0),
            walkingTimeMin = if (best.has("walking_time_min") && !best.isNull("walking_time_min"))
                best.optInt("walking_time_min") else null,
            fare = if (best.has("fare") && !best.isNull("fare")) best.optInt("fare") else null,
            steps = steps
        )
    } catch (e: Exception) {
        Log.e("TransitParse", "route 파싱 실패: ${e.message}")
        null
    }
}

// 🌟 "여기서 / 현재 위치 / 내 위치" 같은 표현 감지
private val CURRENT_LOCATION_KEYWORDS = listOf(
    "여기서", "여기에서", "현재 위치", "현재위치", "내 위치", "내위치",
    "지금 위치", "지금위치", "현 위치", "이 자리", "내가 있는"
)

fun mentionsCurrentLocation(text: String): Boolean =
    CURRENT_LOCATION_KEYWORDS.any { text.contains(it) }

// 🌟 GPS 현재 위치 한 번 가져오기 (권한은 호출 전에 확인되어 있어야 함)
@SuppressLint("MissingPermission")
suspend fun fetchCurrentLocation(context: Context): Location? {
    val hasFine = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) return null

    val client = LocationServices.getFusedLocationProviderClient(context)
    val deferred = CompletableDeferred<Location?>()
    try {
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc -> deferred.complete(loc) }
            .addOnFailureListener { deferred.complete(null) }
    } catch (e: Exception) {
        Log.e("GPS", "위치 요청 실패", e)
        return null
    }
    return deferred.await()
}

fun callSpeedServer(userMessage: String, mode: String = "chat"): SpeedResult? {
    return try {
        val json = JSONObject()
            .put("message", userMessage)
            .put("mode", mode)      // 🌟 "help" 면 서버가 쿠팡/코레일 intent 없이 순수 채팅+MCP만
            .toString()
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(SPEED_SERVER_URL)
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bodyStr = resp.body?.string() ?: return null
            val obj = JSONObject(bodyStr)

            val command = AgentCommand(
                intent = obj.optString("intent", ""),
                query = obj.optString("query", ""),
                confirmationText = obj.optString("msg", "")
            )

            // 🌟 route 가 있으면 함께 파싱 (intent="transit_route" 일 때만 채워짐)
            val routeObj = obj.optJSONObject("route")
            val route = if (routeObj != null) parseTransitRoute(routeObj) else null

            SpeedResult(command = command, route = route)
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

// 🌟 점 세 개 춤추는 로딩 애니메이션 (메신저 "입력 중..." 스타일)
@Composable
fun TypingIndicator(
    dotColor: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val density = LocalDensity.current

    // 점마다 시간차로 위아래로 움직이는 애니메이션
    val dotOffsets = (0..2).map { index ->
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1000     // 한 사이클 1초
                    0f at 0 using FastOutSlowInEasing
                    -10f at 300 using FastOutSlowInEasing   // 위로 10px 튀어오름
                    0f at 600 using FastOutSlowInEasing
                    0f at 1000              // 나머지는 정지
                },
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(index * 160)   // 점마다 160ms 시간차
            ),
            label = "dot$index"
        )
    }

    // 채팅 버블 모양 안에 점 세 개
    Box(
        modifier = modifier
            .background(BubbleBotBg, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            dotOffsets.forEach { offsetAnim ->
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .graphicsLayer {
                            translationY = offsetAnim.value
                        }
                        .background(dotColor, CircleShape)
                )
            }
        }
    }
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

// ===============================================
// 🌟 사이드 메뉴 (드로어)
// ===============================================

@Composable
fun DrawerContent(
    accentColor: Color,
    currentScreen: DrawerScreen,
    onScreenChange: (DrawerScreen) -> Unit
) {
    // 메뉴 ↔ 가이드 상세 좌우 슬라이드 전환
    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            if (targetState == DrawerScreen.MENU) {
                // 상세 → 메뉴 (뒤로): 왼쪽에서 들어옴
                slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
            } else {
                // 메뉴 → 상세 (앞으로): 오른쪽에서 들어옴
                slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
            }
        },
        label = "drawerScreen"
    ) { screen ->
        when (screen) {
            DrawerScreen.MENU -> DrawerMenuScreen(
                accentColor = accentColor,
                onGuideClick = { onScreenChange(it) }
            )
            DrawerScreen.COUPANG_GUIDE -> GuideDetailScreen(
                accentColor = accentColor,
                emoji = "🛒",
                title = "쿠팡 초기설정",
                intro = "쿠팡에 로그인이 되어 있어야\nHelpAgent가 장보기를 도와드릴 수 있어요.",
                steps = listOf(
                    "Play 스토어에서 '쿠팡'을 검색해 설치해요.",
                    "쿠팡 앱을 열고 오른쪽 아래 '마이쿠팡'을 눌러요.",
                    "'로그인'을 누르고, 계정이 없으면 '회원가입'을 눌러요.",
                    "이름, 이메일, 비밀번호, 휴대폰 번호를 입력하고 인증번호를 받으면 가입 완료!",
                    "처음 한 번은 집 주소(배송지)와 결제할 카드를 등록해 주세요.",
                    "여기까지 해 두면 다음부터는 \"라면 사줘\" 한마디로 주문할 수 있어요!"
                ),
                onBack = { onScreenChange(DrawerScreen.MENU) }
            )
            DrawerScreen.KORAIL_GUIDE -> GuideDetailScreen(
                accentColor = accentColor,
                emoji = "🚄",
                title = "코레일톡 초기설정",
                intro = "코레일톡에 로그인이 되어 있어야\nHelpAgent가 기차표 예매를 도와드릴 수 있어요.",
                steps = listOf(
                    "Play 스토어에서 '코레일톡'을 검색해 설치해요.",
                    "앱을 열고 오른쪽 위 메뉴(☰)에서 '로그인'을 눌러요.",
                    "회원이 아니면 '회원가입'을 누르고 이름과 휴대폰 번호로 인증해요.",
                    "비밀번호를 정한 뒤 로그인까지 해 두세요.",
                    "기차표 결제에 쓸 카드를 미리 준비해 두면 예매가 더 빨라요.",
                    "여기까지 해 두면 \"기차표 예매해줘\" 한마디로 시작할 수 있어요!"
                ),
                onBack = { onScreenChange(DrawerScreen.MENU) }
            )
        }
    }
}

// 🌟 드로어 첫 화면: 타이틀 + 큰 가이드 버튼 2개 + 데모 채팅 기록
@Composable
fun DrawerMenuScreen(
    accentColor: Color,
    onGuideClick: (DrawerScreen) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        // 앱 이름
        Text(
            text = "HelpAgent",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = accentColor
        )
        Text(
            text = "당신 곁의 AI 도우미",
            fontSize = 14.sp,
            color = TextGray,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "초기 설정 하는 법",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = TextDark
        )

        Spacer(Modifier.height(12.dp))

        // 🌟 큰 버튼 1: 쿠팡
        BigGuideButton(
            emoji = "🛒",
            title = "쿠팡 초기설정",
            subtitle = "장보기 준비하기",
            accentColor = accentColor,
            onClick = { onGuideClick(DrawerScreen.COUPANG_GUIDE) }
        )

        Spacer(Modifier.height(12.dp))

        // 🌟 큰 버튼 2: 코레일톡
        BigGuideButton(
            emoji = "🚄",
            title = "코레일톡 초기설정",
            subtitle = "기차표 예매 준비하기",
            accentColor = accentColor,
            onClick = { onGuideClick(DrawerScreen.KORAIL_GUIDE) }
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 22.dp),
            color = CardLineColor
        )

        // 🌟 데모용 채팅 기록
        Text(
            text = "채팅 기록",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = TextDark
        )

        Spacer(Modifier.height(10.dp))

        DEMO_CHAT_HISTORY.forEach { history ->
            DemoChatHistoryItem(history = history)
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(16.dp))
    }
}

// 🌟 모서리 둥근 큰 가이드 버튼
@Composable
fun BigGuideButton(
    emoji: String,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    // 반투명 색을 흰색 위에 미리 합성해서 불투명한 색으로 만듦
    // (반투명 배경 + shadow 조합이면 그림자가 배경 뒤로 비쳐서 회색 띠처럼 보임)
    val buttonBg = accentColor.copy(alpha = 0.08f).compositeOver(Color.White)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .shadow(2.dp, RoundedCornerShape(20.dp))
            .background(buttonBg, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 이모지 동그라미
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text = emoji, fontSize = 23.sp)
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = TextGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(26.dp)
        )
    }
}

// 🌟 데모용 채팅 기록 한 줄
@Composable
fun DemoChatHistoryItem(history: DemoChatHistory) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF7F7F7), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = history.title,
            fontSize = 15.sp,
            color = TextDark,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = history.date,
            fontSize = 12.sp,
            color = TextGray,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

// 🌟 가이드 상세 화면 (뒤로가기 + 번호 매긴 단계들)
@Composable
fun GuideDetailScreen(
    accentColor: Color,
    emoji: String,
    title: String,
    intro: String,
    steps: List<String>,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        // 상단: 뒤로가기 + 제목
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(accentColor.copy(alpha = 0.1f), CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "뒤로",
                    tint = accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(text = emoji, fontSize = 22.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark
            )
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = intro,
            fontSize = 14.sp,
            color = TextGray,
            lineHeight = 21.sp
        )

        Spacer(Modifier.height(18.dp))

        // 단계별 안내
        steps.forEachIndexed { index, step ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF7F7F7), RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(accentColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${index + 1}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = step,
                    fontSize = 15.sp,
                    color = TextDark,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(20.dp))
    }
}

// 🌟 가로로 긴 토글 스위치 (Security / Bolt 아이콘)
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
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
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

// ===============================================
// 🌟 길찾기 카드 UI
// ===============================================

// step 의 mode 에 따라 (아이콘, 색, 칩 텍스트색) 매핑
private data class StepStyle(val icon: ImageVector, val color: Color)

private fun styleForMode(mode: String): StepStyle = when (mode) {
    "subway" -> StepStyle(Icons.Filled.Train, SubwayBlue)
    "bus" -> StepStyle(Icons.Filled.DirectionsBus, BusGreen)
    "walk" -> StepStyle(Icons.Filled.DirectionsWalk, WalkGray)
    else -> StepStyle(Icons.Filled.LocationOn, WalkGray)
}

@Composable
fun TransitRouteCard(route: TransitRoute) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(20.dp))
            .background(Color.White, RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Column {
            // 헤더: 출발 → 도착
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = route.from,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text("  →  ", fontSize = 16.sp, color = TextGray)
                Text(
                    text = route.to,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            Spacer(Modifier.height(10.dp))

            // 요약 칩: 소요시간 / 환승 / 요금
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                route.totalTimeMin?.let { InfoChip("⏱ 약 ${it}분") }
                InfoChip("🔁 환승 ${route.transferCount}회")
                route.fare?.let { InfoChip("💳 ${formatFare(it)}원") }
            }

            Spacer(Modifier.height(16.dp))

            // 타임라인: step 순회
            route.steps.forEachIndexed { index, step ->
                TransitStepRow(
                    step = step,
                    isLast = false
                )
            }
            // 마지막 도착 노드
            TransitArriveRow(route.to)
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = TextDark.copy(alpha = 0.7f),
        modifier = Modifier
            .background(ChipBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun TransitStepRow(step: TransitStep, isLast: Boolean) {
    val style = styleForMode(step.mode)
    Row(modifier = Modifier.fillMaxWidth()) {
        // 왼쪽: 아이콘 + 세로선
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(style.color, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(28.dp)
                    .background(CardLineColor)
            )
        }

        Spacer(Modifier.width(14.dp))

        // 오른쪽: 내용
        Column(modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
            when (step.mode) {
                "walk" -> {
                    Text("걷기", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextDark)
                    step.timeMin?.let {
                        Text("약 ${it}분", fontSize = 14.sp, color = TextGray)
                    }
                }
                else -> {
                    // 노선 칩
                    Text(
                        text = step.line,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = style.color,
                        modifier = Modifier
                            .background(style.color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                    Spacer(Modifier.height(5.dp))

                    // 🌟 mode 에 따라 승차 안내 문구 (지하철역 / 버스정류장)
                    val boardLabel = if (step.mode == "subway") "지하철역에서 타기" else "정류장에서 버스 타기"
                    Text(boardLabel, fontSize = 13.sp, color = TextGray)
                    Spacer(Modifier.height(3.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(step.from, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextDark)
                        Text(" 에서 타기", fontSize = 15.sp, color = TextGray)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(step.to, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextDark)
                        Text(" 에서 내리기", fontSize = 15.sp, color = TextGray)
                    }
                    if (step.stations > 0) {
                        Text("${step.stations}개 정거장", fontSize = 14.sp, color = TextGray)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransitArriveRow(destination: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(ArriveOrange, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = "$destination 도착",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = TextDark,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

// 1850 → "1,850"
private fun formatFare(fare: Int): String =
    fare.toString().reversed().chunked(3).joinToString(",").reversed()

@Composable
fun ChatScreen(generateResponse: (String) -> String) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
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

    // 🌟 사이드 메뉴(드로어) 상태
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var drawerScreen by remember { mutableStateOf(DrawerScreen.MENU) }

    // 🌟 드로어 닫히면 다음에 열 때 항상 메뉴 화면부터
    LaunchedEffect(drawerState.isClosed) {
        if (drawerState.isClosed) drawerScreen = DrawerScreen.MENU
    }

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

    // 🌟 전송 함수 참조 (런처 콜백에서 호출하기 위해 ref 로 우회)
    val submitMessageRef = remember { mutableStateOf<((String) -> Unit)?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // 🌟 권한 허용 후 다시 보낼 질문 보관
    var pendingLocationText by remember { mutableStateOf<String?>(null) }

    // 🌟 위치 권한 런처: 허용되면 보관해둔 질문을 자동으로 다시 전송
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }
        val pending = pendingLocationText
        pendingLocationText = null
        if (granted && pending != null) {
            submitMessageRef.value?.invoke(pending)
        } else if (!granted) {
            messages = messages + Message(
                "위치 권한이 없어서 현재 위치를 쓸 수 없어요. 출발지를 직접 말씀해 주세요.",
                false
            )
            isLoading = false
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

    // 🌟 메시지 전송 처리 (전송 버튼 / 권한 허용 후 재실행 양쪽에서 호출)
    fun submitMessage(text: String) {
        val wantsCurrentLoc = mentionsCurrentLocation(text)

        // 현재 위치가 필요한데 권한이 없으면 → 질문 보관 후 권한 요청하고 종료
        if (wantsCurrentLoc) {
            val hasFine = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasFine && !hasCoarse) {
                pendingLocationText = text          // 허용되면 이 질문을 다시 전송
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
                return
            }
        }

        coroutineScope.launch {
            // 🌟 현재 위치 요청이면 GPS 좌표를 얻어 출발지로 치환
            var sendText = text
            if (wantsCurrentLoc) {
                val loc = withContext(Dispatchers.IO) { fetchCurrentLocation(context) }
                if (loc != null) {
                    val tag = "@현재위치@${loc.latitude},${loc.longitude}"
                    for (kw in CURRENT_LOCATION_KEYWORDS) {
                        sendText = sendText.replace(kw, tag)
                    }
                    Log.d("GPS", "치환된 메시지: $sendText")
                } else {
                    messages = messages + Message(
                        "현재 위치를 가져오지 못했어요. 출발지를 직접 말씀해 주세요.",
                        false
                    )
                    isLoading = false
                    return@launch
                }
            }

            // 🌟 빠른 모드: 서버 호출 (명령 + 선택적 길찾기 경로)
            val speedResult: SpeedResult? = if (currentMode == AgentMode.SPEED) {
                withContext(Dispatchers.IO) { callSpeedServer(sendText) }
            } else null

            val cmd: AgentCommand? = if (currentMode == AgentMode.SPEED) {
                speedResult?.command
            } else {
                val prompt = buildAdvancedPrompt(sendText)
                val reply = withContext(Dispatchers.IO) { generateResponse(prompt) }
                parseLlmResponse(reply)
            }

            if (cmd != null && cmd.intent.isNotEmpty()) {
                when (cmd.intent) {
                    // 🌟 길찾기: 카드로 렌더
                    "transit_route" -> {
                        val route = speedResult?.route
                        if (route != null) {
                            if (cmd.confirmationText.isNotBlank()) {
                                messages = messages + Message(cmd.confirmationText, false)
                            }
                            messages = messages + Message("", false, route = route)
                        } else {
                            messages = messages + Message(
                                cmd.confirmationText.ifBlank { "경로를 찾지 못했어요." },
                                false
                            )
                        }
                    }
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
    }

    // 🌟 런처 콜백이 호출할 수 있도록 ref 연결
    submitMessageRef.value = { t -> submitMessage(t) }

    // 🌟 입력창 내용 전송 (전송 버튼 / 키보드 엔터 양쪽에서 호출)
    fun sendCurrentInput(hideKeyboard: Boolean) {
        if (inputText.isBlank() || isLoading) return
        if (hideKeyboard) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
        val text = inputText
        inputText = ""
        isLoading = true
        messages = messages + Message(text, true)
        submitMessage(text)
    }

    LaunchedEffect(context) {
        hasPermission = isAccessibilityServiceEnabled(context, AutoAgentService::class.java)
    }

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

    // 🌟 사이드 메뉴(드로어)로 전체 화면 감싸기
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,   // 열려있을 때만 스와이프로 닫기 (채팅 스크롤과 충돌 방지)
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.8f),        // 🌟 세로 꽉 채우고 가로 80%
                drawerContainerColor = Color.White,
                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
            ) {
                DrawerContent(
                    accentColor = accentColor,
                    currentScreen = drawerScreen,
                    onScreenChange = { drawerScreen = it }
                )
            }
        }
    ) {
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
                        coroutineScope.launch { drawerState.open() }
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
                    if (msg.route != null) {
                        // 🌟 길찾기 카드 (말풍선 대신)
                        TransitRouteCard(route = msg.route)
                    } else {
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
                }

                // 🌟 로딩 인디케이터: 점 세 개 춤추기
                if (isLoading) {
                    item {
                        Box(
                            Modifier.fillMaxWidth(),
                            Alignment.CenterStart
                        ) {
                            TypingIndicator(dotColor = accentColor)
                        }
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

                item { Spacer(Modifier.height(4.dp)) }
            }

            // 🌟 입력창 영역
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
                        onSend = { sendCurrentInput(hideKeyboard = true) },   // 🌟 엔터 → 전송 + 키보드 내림
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(Modifier.width(10.dp))

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(accentColor, CircleShape)
                            .clickable {
                                sendCurrentInput(hideKeyboard = true)         // 🌟 버튼 → 전송 + 키보드 내림
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
}

@Composable
fun BasicTextFieldWrapper(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,                                            // 🌟 줄바꿈 대신 전송 키
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), // 🌟 엔터키 → 전송(보내기) 아이콘
        keyboardActions = KeyboardActions(onSend = { onSend() }),      // 🌟 전송 키 동작 연결
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