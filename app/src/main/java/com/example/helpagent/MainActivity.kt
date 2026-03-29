package com.example.helpagent

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.io.File
import java.io.FileOutputStream

data class Message(val text: String, val isUser: Boolean)


// 앱 전체 테마 색상
val ThemePurple = Color(0xFF9C27B0)
val ThemeBackground = Color(0xFFF0F2F5)

class MainActivity : ComponentActivity() {
    external fun loadWhisperModel(modelPath: String): Boolean
    external fun transcribeAudio(samples: FloatArray): String
    external fun releaseWhisperModel()

    private fun copyAssetToInternalStorage(assetName: String, subDir: String = "models"): String {
        val outDir = File(filesDir, subDir)
        if (!outDir.exists()) outDir.mkdirs()

        val outFile = File(outDir, assetName.substringAfterLast('/'))
        if (!outFile.exists()) {
            assets.open(assetName).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return outFile.absolutePath
    }

    companion object {
        private const val RECORD_AUDIO_PERMISSION_CODE = 1001
    }

    init {
        System.loadLibrary("native-lib")
    }

    external fun stringFromJNI(): String

    private fun ensureAudioPermission() {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_CODE
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ensureAudioPermission()

        val modelPath = copyAssetToInternalStorage("models/ggml-tiny.bin")
        val loaded = loadWhisperModel(modelPath)
        Log.d("WHISPER", "model loaded = $loaded, path = $modelPath")

        setContent {
            ChatScreen(
                recordAudio = ::recordAudio3Seconds,
                transcribeAudio = ::transcribeAudio
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWhisperModel()
    }
}

/**
 * 16kHz, mono, PCM 16bit 로 3초 녹음 후 FloatArray 반환
 */
fun recordAudio3Seconds(): FloatArray {
    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    val minBufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        channelConfig,
        audioFormat
    )

    require(
        minBufferSize != AudioRecord.ERROR_BAD_VALUE &&
                minBufferSize != AudioRecord.ERROR
    ) {
        "유효한 오디오 버퍼 크기를 가져오지 못했습니다."
    }

    val recorder = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        sampleRate,
        channelConfig,
        audioFormat,
        minBufferSize
    )

    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        throw IllegalStateException("AudioRecord 초기화 실패")
    }

    val seconds = 3
    val totalSamples = sampleRate * seconds
    val shortBuffer = ShortArray(totalSamples)

    recorder.startRecording()

    var offset = 0
    while (offset < totalSamples) {
        val read = recorder.read(shortBuffer, offset, totalSamples - offset)
        if (read > 0) {
            offset += read
        }
    }

    recorder.stop()
    recorder.release()

    return FloatArray(totalSamples) { i ->
        shortBuffer[i] / 32768.0f
    }
}

@Composable
fun ChatScreen(
    recordAudio: () -> FloatArray,
    transcribeAudio: (FloatArray) -> String
) {
    var messages by remember {
        mutableStateOf(listOf(Message("안녕하세요? 무엇을 도와드릴까요?", false)))
    }
    var inputText by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        messages = messages + Message(text, true)

        coroutineScope.launch {
            delay(500)
            messages = messages + Message("확인했어요: $text", false)
        }
    }

    LaunchedEffect(Unit) {
        try {
            delay(300)
            focusRequester.requestFocus()
            keyboardController?.show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ThemeBackground)
            .systemBarsPadding()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ThemeBackground)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .border(1.dp, Color.White, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "AI 파트너",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.Black
                )
            }

            TextButton(
                onClick = {
                    messages = listOf(Message("안녕하세요? 무엇을 도와드릴까요?", false))
                },
                modifier = Modifier
                    .height(36.dp)
                    .border(1.dp, Color.White, RoundedCornerShape(50)),
                colors = ButtonDefaults.textButtonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
            ) {
                Text(
                    text = "새 채팅",
                    color = ThemePurple,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(messages) { message ->
                MessageBubble(message)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ThemeBackground)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text("메시지를 입력하세요...") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                trailingIcon = {
                    Box(modifier = Modifier.padding(end = 4.dp)) {

                        if (inputText.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    val userText = inputText
                                    inputText = ""
                                    sendMessage(userText)
                                },
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(ThemePurple, shape = CircleShape)
                            ) {
                                Text(
                                    text = "^",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        try {
                                            messages = messages + Message("3초간 녹음 시작...", false)

                                            val audioSamples = withContext(Dispatchers.IO) {
                                                recordAudio()
                                            }

                                            val recognizedText = transcribeAudio(audioSamples)

                                            sendMessage(recognizedText)

                                        } catch (e: Exception) {
                                            messages = messages + Message(
                                                "녹음 실패: ${e.message}",
                                                false
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(ThemePurple, shape = CircleShape)
                            ) {
                                Text(text = "🎤", color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun MessageBubble(message: Message) {
    val alignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    val backgroundColor = if (message.isUser) ThemePurple else Color.White
    val textColor = if (message.isUser) Color.White else Color.Black

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Text(
            text = message.text,
            color = textColor,
            modifier = Modifier
                .background(backgroundColor, RoundedCornerShape(16.dp))
                .padding(12.dp)
        )
    }
}