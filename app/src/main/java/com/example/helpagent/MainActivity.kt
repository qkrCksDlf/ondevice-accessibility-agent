package com.example.helpagent

import android.os.Bundle
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class Message(val text: String, val isUser: Boolean)

// 앱 전체 테마 색상
val ThemePurple = Color(0xFF9C27B0)
val ThemeBackground = Color(0xFFF0F2F5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChatScreen()
        }
    }
}

@Composable
fun ChatScreen() {
    var messages by remember { mutableStateOf(listOf(Message("안녕하세요? 무엇을 도와드릴까요?", false))) }
    var inputText by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

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
        // 상단 바 (Header)
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
                Text(text = "새 채팅", color = ThemePurple, fontWeight = FontWeight.Bold)
            }
        }

        // 메시지 목록 영역
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

        // 하단 입력창 영역
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
                    // [1] 버튼 영역의 시작 (우측 여백만 살짝 줍니다)
                    Box(modifier = Modifier.padding(end = 4.dp)) {

                        // [2] 조건문: 글자가 한 글자라도 입력되었다면 전송 버튼을 보여줍니다.
                        if (inputText.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    val userText = inputText
                                    messages = messages + Message(userText, true)
                                    inputText = "" // 전송 후 입력창을 비우면 자동으로 마이크 버튼으로 돌아갑니다.

                                    coroutineScope.launch {
                                        delay(500)
                                        messages = messages + Message(userText, false)
                                    }
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
                        }
                        // [3] 조건문: 글자가 비어있다면 마이크 버튼을 보여줍니다.
                        else {
                            IconButton(
                                onClick = {
                                    messages = messages + Message("음성인식!", false)
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