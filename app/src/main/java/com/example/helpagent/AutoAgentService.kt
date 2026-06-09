package com.example.helpagent

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.accessibilityservice.GestureDescription
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class AutoAgentService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var automationJob: Job? = null

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    private var userActionDeferred: CompletableDeferred<String>? = null
    private var currentQuery: String = ""

    private var ktxDeparture: String = ""
    private var ktxArrival: String = ""

    private var ktxQuestionView: TextView? = null
    private var ktxInputView: EditText? = null
    private var ktxMicView: TextView? = null
    private var ktxButtonRow: LinearLayout? = null
    private var ktxInputRow: LinearLayout? = null
    private var ktxIsListening: Boolean = false
    private var ktxRecognizer: SpeechRecognizer? = null
    private var ktxInputDeferred: CompletableDeferred<String>? = null

    companion object {
        const val THEME_PURPLE = "#9C27B0"
        const val THEME_BACKGROUND = "#F0F2F5"
        const val BUBBLE_BOT_BG = "#FFFFFF"
        const val TEXT_DARK = "#222222"
        const val TEXT_GRAY = "#666666"
        const val CANCEL_RED = "#F44336"
        const val AUTO_GREEN = "#4CAF50"

        const val KORAIL_PACKAGE = "com.korail.talk"
        const val ID_DEPARTURE_STATION = "com.korail.talk:id/v_departure_station"
        const val ID_ARRIVAL_STATION = "com.korail.talk:id/v_arrival_station"
        const val ID_GOING_DATE = "com.korail.talk:id/rl_going_date"
        const val ID_ROUND_TRIP_CHECKBOX = "com.korail.talk:id/cb_round_trip"
        const val ID_SEARCH_BUTTON = "com.korail.talk:id/btn_right"
        const val ID_STATION_NAME_TXT = "com.korail.talk:id/stationNameTxt"
    }

    enum class AgentState {
        WAIT_FOR_RESULTS_LOAD, WAIT_USER_PICK, WAIT_USER_BUY_DECISION,
        AUTO_WAIT_RESULTS, AUTO_CLICK_FIRST_ITEM, AUTO_WAIT_DETAIL,
        CLICK_BUY_NOW, WAIT_FOR_PAYMENT_PAGE, DONE, CANCELLED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AutoAgent", "서비스 연결됨")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        serviceScope.launch {
            AgentController.commandFlow.collect { command ->
                when (command.intent) {
                    "buy_product", "select_item" -> runCoupangAutomation(command.query, command.autoMode)
                    "book_ktx" -> {
                        when (command.stage) {
                            "launch" -> runKtxFlow()
                        }
                    }
                }
            }
        }
    }

    // ============================================
    // 🌟 코레일톡 윈도우 root
    // ============================================
    private fun getKorailRoot(): AccessibilityNodeInfo? {
        val allWindows = windows ?: return null
        val korailWindows = allWindows.filter {
            it.root?.packageName?.toString() == KORAIL_PACKAGE
        }
        if (korailWindows.isEmpty()) return null
        return korailWindows.firstOrNull { it.isFocused }?.root
            ?: korailWindows.last().root
    }

    // ============================================
    // 🌟 진단 로그
    // ============================================
    private fun debugDumpScreen(label: String) {
        Log.d("KtxDebug", "===== [$label] 시작 =====")
        val allWindows = windows
        if (allWindows == null) {
            Log.d("KtxDebug", "[$label] windows == null")
            Log.d("KtxDebug", "===== [$label] 끝 =====")
            return
        }
        Log.d("KtxDebug", "[$label] 윈도우 개수: ${allWindows.size}")
        for ((idx, window) in allWindows.withIndex()) {
            val root = window.root
            val pkg = root?.packageName?.toString() ?: "null"
            val type = window.type
            val title = window.title?.toString() ?: "null"
            Log.d("KtxDebug", "  window[$idx] pkg=$pkg type=$type title=$title focused=${window.isFocused}")
            if (root != null && pkg == KORAIL_PACKAGE) {
                val nodes = root.findAllNodes()
                nodes.forEach { node ->
                    val t = node.text?.toString() ?: ""
                    val d = node.contentDescription?.toString() ?: ""
                    val id = node.viewIdResourceName ?: ""
                    if (t.isNotBlank() || d.isNotBlank() || id.isNotBlank()) {
                        Log.d("KtxDebug", "    text='$t' desc='$d' id=$id clickable=${node.isClickable}")
                    }
                }
            }
        }
        Log.d("KtxDebug", "===== [$label] 끝 =====")
    }

    // ============================================
    // 🌟 노드 찾기 헬퍼
    // ============================================
    private fun findById(resourceId: String): AccessibilityNodeInfo? {
        val root = getKorailRoot() ?: return null
        return root.findAccessibilityNodeInfosByViewId(resourceId).firstOrNull()
    }

    private fun clickById(resourceId: String): Boolean {
        val node = findById(resourceId)
        Log.d("KtxFlow", "clickById($resourceId): found=${node != null}")
        return node?.performClickRecursive() ?: false
    }

    private fun findExactText(target: String): AccessibilityNodeInfo? {
        val root = getKorailRoot() ?: return null
        val nodes = root.findAccessibilityNodeInfosByText(target)
        val exactMatches = nodes.filter { it.text?.toString()?.trim() == target }
        val withClickable = exactMatches.firstOrNull { findClickableAncestor(it) != null }
        return withClickable ?: exactMatches.firstOrNull()
    }

    private fun findStationInList(stationName: String): AccessibilityNodeInfo? {
        val root = getKorailRoot() ?: return null
        val allNodes = root.findAllNodes()
        val candidates = allNodes.filter {
            it.viewIdResourceName == ID_STATION_NAME_TXT &&
                    it.text?.toString()?.trim() == stationName
        }
        Log.d("KtxFlow", "findStationInList('$stationName'): ${candidates.size}개 발견")
        candidates.forEachIndexed { i, node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            Log.d("KtxFlow", "  [$i] bounds=$rect clickable=${node.isClickable}")
        }
        return candidates.firstOrNull()
    }

    private fun findFirstEditable(): AccessibilityNodeInfo? {
        val root = getKorailRoot() ?: return null
        return root.findAllNodes().firstOrNull {
            it.isEditable || it.className?.toString() == "android.widget.EditText"
        }
    }

    private fun setEditTextValue(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
        )
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun clickSearchButton(): Boolean {
        val root = getKorailRoot() ?: return false
        val target = root.findAllNodes().firstOrNull {
            val t = it.text?.toString() ?: ""
            val d = it.contentDescription?.toString() ?: ""
            t == "검색" || d == "검색" || d.contains("search", ignoreCase = true)
        }
        Log.d("KtxFlow", "clickSearchButton: found=${target != null}")
        return target?.performClickRecursive() ?: false
    }

    // ============================================
    // 🌟 텍스트로 버튼 클릭 (정확 일치 → 부분 일치)
    // ============================================
    private fun clickByText(vararg texts: String): Boolean {
        val root = getKorailRoot() ?: return false
        val allNodes = root.findAllNodes()
        for (target in texts) {
            val exact = allNodes.firstOrNull {
                it.text?.toString()?.trim() == target ||
                        it.contentDescription?.toString()?.trim() == target
            }
            if (exact != null && exact.performClickRecursive()) {
                Log.d("KtxFlow", "clickByText 정확 '$target' 성공")
                return true
            }
            val partial = allNodes.firstOrNull {
                it.text?.toString()?.contains(target) == true ||
                        it.contentDescription?.toString()?.contains(target) == true
            }
            if (partial != null && partial.performClickRecursive()) {
                Log.d("KtxFlow", "clickByText 부분 '$target' 성공")
                return true
            }
        }
        Log.w("KtxFlow", "clickByText 실패: ${texts.joinToString()}")
        return false
    }

    // ============================================
    // 🌟 마일리지 잔액 추출
    // ============================================
    private fun extractMileage(): Int {
        val root = getKorailRoot() ?: return 0

        // 1) 전용 ID 우선 (가장 정확) — 잔액은 tv_ktx_mileage_total 에 따로 들어있음
        root.findAccessibilityNodeInfosByViewId("com.korail.talk:id/tv_ktx_mileage_total")
            .firstOrNull()?.text?.toString()?.let { raw ->
                val num = raw.replace(",", "").filter { it.isDigit() }
                if (num.isNotEmpty()) num.toIntOrNull()?.let {
                    Log.d("KtxFlow", "마일리지 (ID): $it ('$raw')")
                    return it
                }
            }

        val allNodes = root.findAllNodes()

        // 2) fallback: "마일리지" 가 들어간 텍스트 안에서 숫자 추출
        val mileageTexts = allNodes.mapNotNull { it.text?.toString() }
            .filter { it.contains("마일리지") }
        for (t in mileageTexts) {
            val num = t.replace(",", "").filter { it.isDigit() }
            if (num.isNotEmpty()) num.toIntOrNull()?.let {
                Log.d("KtxFlow", "마일리지 (동일 노드): $it ('$t')")
                return it
            }
        }
        // "마일리지" 라벨 근처 숫자 노드에서 추출
        val nearby = collectTextsNearLabel(allNodes, listOf("마일리지"))
        for (t in nearby) {
            val num = t.replace(",", "").replace("점", "").trim().filter { it.isDigit() }
            if (num.isNotEmpty()) num.toIntOrNull()?.let {
                Log.d("KtxFlow", "마일리지 (근처 노드): $it ('$t')")
                return it
            }
        }
        Log.d("KtxFlow", "마일리지 못 찾음 → 0")
        return 0
    }

    // ============================================
    // 🌟 화면 맨 아래로 스크롤
    // ============================================
    private suspend fun scrollToBottom() {
        for (i in 1..10) {
            val root = getKorailRoot() ?: break
            val scrollable = root.findAllNodes().firstOrNull { it.isScrollable } ?: break
            val scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Log.d("KtxFlow", "scrollToBottom #$i: $scrolled")
            if (!scrolled) break
            delay(400)
        }
    }

    // ============================================
    // 🌟 코레일톡 시작 팝업 닫기
    // ============================================
    private fun dismissKorailStartPopup(): Boolean {
        val root = getKorailRoot() ?: return false
        val allNodes = root.findAllNodes()

        // 팝업 감지: "오늘 그만 보기" 또는 "닫기" 가 화면에 있는지
        val hasPopup = allNodes.any {
            val t = it.text?.toString() ?: ""
            val d = it.contentDescription?.toString() ?: ""
            t.contains("오늘 그만") || d.contains("오늘 그만") ||
                    t.trim() == "닫기" || d.trim() == "닫기"
        }
        if (!hasPopup) {
            Log.d("KtxFlow", "시작 팝업 없음 → 출발역 진행")
            return false
        }

        // "닫기" 버튼 클릭
        val closeNode = allNodes.firstOrNull {
            val t = it.text?.toString()?.trim() ?: ""
            val d = it.contentDescription?.toString()?.trim() ?: ""
            t == "닫기" || d == "닫기"
        }
        if (closeNode == null) {
            Log.w("KtxFlow", "팝업 감지됨, '닫기' 버튼 못 찾음")
            return false
        }
        val ok = closeNode.performClickRecursive()
        Log.d("KtxFlow", "시작 팝업 '닫기' 클릭: $ok")
        return ok
    }

    // ============================================
    // 🌟 KTX 전체 흐름
    // ============================================
    private fun runKtxFlow() {
        Log.d("KtxFlow", "🌟 runKtxFlow 호출됨")
        val intent = packageManager.getLaunchIntentForPackage(KORAIL_PACKAGE)
        if (intent == null) {
            serviceScope.launch(Dispatchers.Main) {
                Toast.makeText(applicationContext, "코레일톡이 설치되어 있지 않아요.", Toast.LENGTH_LONG).show()
            }
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)

        automationJob?.cancel()
        automationJob = serviceScope.launch(Dispatchers.Main) {
            try {
                delay(2500)
                debugDumpScreen("코레일톡 메인 진입")

                // ===== 0) 시작 팝업 닫기 =====
                delay(1000)
                for (i in 1..3) {
                    if (dismissKorailStartPopup()) {
                        delay(800)        // 팝업 닫힌 뒤 화면 안정화
                        break
                    }
                    delay(500)            // 팝업이 살짝 늦게 뜰 수 있어 잠깐 더 보고 재확인
                }

                // ===== 1) 출발역 =====
                showKtxInputOverlay("출발역을 입력해주세요\n(예: 서울, 대전, 부산)")
                val departure = waitForInput() ?: run { removeKtxOverlay(); return@launch }
                ktxDeparture = departure
                Log.d("KtxFlow", "출발역: $departure")

                if (!selectStation(departure, isDeparture = true)) {
                    fail("출발역 '$departure' 설정 실패")
                    return@launch
                }

                // ===== 2) 도착역 =====
                showInputRow()
                updateKtxQuestion("도착역을 입력해주세요")
                val arrival = waitForInput() ?: run { removeKtxOverlay(); return@launch }
                ktxArrival = arrival
                Log.d("KtxFlow", "도착역: $arrival")

                if (!selectStation(arrival, isDeparture = false)) {
                    fail("도착역 '$arrival' 설정 실패")
                    return@launch
                }

                // ===== 3) 가는날 =====
                removeKtxOverlay()
                delay(1000)
                if (!clickById(ID_GOING_DATE)) {
                    fail("가는날 영역 못 찾음")
                    return@launch
                }
                delay(2000)

                showKtxGuideOverlay(
                    "갈 날짜를 선택하고 확인 버튼을 누르세요!",
                    showHelpButton = false
                )

                val dateCheckJob = serviceScope.launch {
                    for (i in 1..120) {
                        delay(1000)
                        if (findById(ID_DEPARTURE_STATION) != null &&
                            findById(ID_ROUND_TRIP_CHECKBOX) != null) {
                            Log.d("KtxFlow", "메인 복귀 감지 (${i}초)")
                            ktxInputDeferred?.complete("done")
                            break
                        }
                    }
                }

                val dateResult = waitForGuideAction()
                dateCheckJob.cancel()
                if (dateResult == null) {
                    Log.d("KtxFlow", "사용자가 취소함")
                    removeKtxOverlay()
                    return@launch
                }
                removeKtxOverlay()
                delay(1000)

                // ===== 4) 편도/왕복 =====
                showKtxInputOverlay("편도인가요, 왕복인가요?")
                hideInputRow()
                delay(500)
                showTripTypeButtons()
                val tripType = waitForTripType()
                hideTripTypeButtons()

                if (tripType == "round") {
                    updateKtxQuestion("왕복으로 설정 중...")
                    if (!clickById(ID_ROUND_TRIP_CHECKBOX)) {
                        fail("왕복 체크박스 못 찾음")
                        return@launch
                    }
                    delay(1500)

                    removeKtxOverlay()
                    delay(1000)

                    val returnDateNode = findById("com.korail.talk:id/rl_arrival_date")
                        ?: findById("com.korail.talk:id/rl_return_date")
                        ?: findExactText("오는날")?.let { findClickableAncestor(it) }
                        ?: findExactText("도착일")?.let { findClickableAncestor(it) }
                    if (returnDateNode == null) {
                        fail("오는날 영역 못 찾음")
                        return@launch
                    }
                    returnDateNode.performClickRecursive()
                    delay(2000)

                    showKtxGuideOverlay(
                        "오는 날짜를 선택하고 확인 버튼을 누르세요!",
                        showHelpButton = false
                    )

                    val returnCheckJob = serviceScope.launch {
                        for (i in 1..120) {
                            delay(1000)
                            if (findById(ID_DEPARTURE_STATION) != null &&
                                findById(ID_SEARCH_BUTTON) != null) {
                                Log.d("KtxFlow", "오는날 메인 복귀 (${i}초)")
                                ktxInputDeferred?.complete("done")
                                break
                            }
                        }
                    }

                    val returnResult = waitForGuideAction()
                    returnCheckJob.cancel()
                    if (returnResult == null) {
                        Log.d("KtxFlow", "사용자가 취소함")
                        removeKtxOverlay()
                        return@launch
                    }
                    removeKtxOverlay()
                    delay(1000)
                }

                // ===== 5) 열차조회 =====
                Toast.makeText(applicationContext, "열차 조회 중...", Toast.LENGTH_SHORT).show()
                delay(500)
                if (!clickById(ID_SEARCH_BUTTON)) {
                    fail("열차조회 버튼 못 찾음")
                    return@launch
                }
                delay(2500)
                debugDumpScreen("열차조회 결과 화면")

                // 🌟 안내 + 화면 변화 감지
                showKtxGuideOverlay(
                    "원하는 시간대의 '예매' 버튼을 눌러주세요",
                    showHelpButton = true
                )

                val trainSelectJob = serviceScope.launch {
                    delay(2000)
                    for (i in 1..120) {
                        delay(1000)
                        val root = getKorailRoot() ?: continue
                        val nodes = root.findAllNodes()
                        val isNextScreen = nodes.any {
                            val t = it.text?.toString() ?: ""
                            t.contains("좌석선택") || t.contains("좌석 선택") ||
                                    t.contains("결제하기") || t.contains("예매하기") ||
                                    t.contains("자동배정") || t.contains("간편결제")
                        }
                        if (isNextScreen) {
                            Log.d("KtxFlow", "예매 후 다음 화면 감지 (${i}초)")
                            ktxInputDeferred?.complete("done")
                            break
                        }
                    }
                }

                val reserveResult = waitForGuideAction()
                trainSelectJob.cancel()
                if (reserveResult == null) {
                    Log.d("KtxFlow", "사용자가 취소함")
                    removeKtxOverlay()
                    return@launch
                } else if (reserveResult == "help") {
                    Toast.makeText(applicationContext,
                        "도움 기능은 준비 중입니다.",
                        Toast.LENGTH_SHORT).show()
                    removeKtxOverlay()
                    return@launch
                }
                removeKtxOverlay()
                delay(1000)
                debugDumpScreen("예매 후 화면")

                // ===== 6) 좌석 선택 옵션 =====
                showKtxSeatOptionOverlay()
                val seatChoice = waitForGuideAction()
                if (seatChoice == null) {
                    Log.d("KtxFlow", "사용자가 취소함")
                    removeKtxOverlay()
                    return@launch
                }
                removeKtxOverlay()
                delay(500)

                if (seatChoice == "select_seat") {
                    Log.d("KtxFlow", "좌석 선택 모드")
                    val seatBtn = findExactText("좌석선택")?.let { findClickableAncestor(it) }
                        ?: findExactText("좌석 선택")?.let { findClickableAncestor(it) }
                    if (seatBtn == null) {
                        fail("좌석선택 버튼 못 찾음")
                        return@launch
                    }
                    seatBtn.performClickRecursive()
                    delay(2000)
                    debugDumpScreen("좌석 선택 화면")

                    showKtxGuideOverlay(
                        "원하는 좌석을 그림에서 선택하고\n아래의 '선택 완료' 버튼을 누르세요.\n위에서 다른 칸으로 바꿀 수 있습니다.",
                        showHelpButton = false,
                        topPosition = true
                    )

                    val seatDoneJob = serviceScope.launch {
                        delay(3000)
                        for (i in 1..120) {
                            delay(1000)
                            val root = getKorailRoot() ?: continue
                            val nodes = root.findAllNodes()
                            val backToList = nodes.any {
                                val t = it.text?.toString() ?: ""
                                t.contains("자동배정") || t.contains("결제하기") || t.contains("예매하기")
                            } && nodes.none {
                                val t = it.text?.toString() ?: ""
                                t.contains("선택완료") || t.contains("선택 완료")
                            }
                            if (backToList) {
                                Log.d("KtxFlow", "좌석 선택 완료 후 복귀 감지 (${i}초)")
                                ktxInputDeferred?.complete("done")
                                break
                            }
                        }
                    }

                    val seatDoneResult = waitForGuideAction()
                    seatDoneJob.cancel()
                    if (seatDoneResult == null) {
                        Log.d("KtxFlow", "사용자가 취소함")
                        removeKtxOverlay()
                        return@launch
                    }
                    removeKtxOverlay()
                    delay(1000)
                    debugDumpScreen("좌석 선택 후 화면")
                }

                // ===== 7) 예매하기 클릭 → 결제 화면 진입 =====
                Toast.makeText(applicationContext, "결제 화면으로 이동할게요...", Toast.LENGTH_SHORT).show()
                delay(500)

                val reserveBtn = findExactText("예매하기")?.let { findClickableAncestor(it) }
                    ?: findExactText("예매")?.let { findClickableAncestor(it) }
                if (reserveBtn != null) {
                    reserveBtn.performClickRecursive()
                    Log.d("KtxFlow", "예매하기 버튼 클릭")
                    delay(2500)
                } else {
                    Log.w("KtxFlow", "예매하기 버튼 못 찾음 (이미 결제화면일 수도)")
                }
                debugDumpScreen("결제 화면 진입")

                // ===== 8) 결제하기 버튼 클릭 =====
                if (!(clickById("com.korail.talk:id/btn_reservation_confirm_right") || clickByText("결제하기"))) {
                    fail("결제하기 버튼 못 찾음")
                    return@launch
                }
                delay(2500)
                debugDumpScreen("결제 정보 확인 화면")

                // ===== 9) 결제 정보 확인 오버레이 =====
                showKtxConfirmOverlay(
                    "코레일톡 화면에 예매 정보가 나와 있어요.\n" +
                            "출발·도착역, 날짜, 시간, 인원, 금액이\n" +
                            "맞는지 꼭 확인해주세요!"
                )
                if (waitForGuideAction() == null) {
                    Log.d("KtxFlow", "결제 정보 확인에서 취소함")
                    removeKtxOverlay()
                    return@launch
                }
                removeKtxOverlay()
                delay(500)

                // ===== 10) 다음 버튼 =====
                if (!clickByText("다음")) {
                    fail("다음 버튼 못 찾음")
                    return@launch
                }
                delay(2000)
                debugDumpScreen("마일리지/결제수단 화면")

                // ===== 11) 마일리지 처리 =====
                val mileage = extractMileage()
                Log.d("KtxFlow", "감지된 마일리지: $mileage")
                if (mileage >= 100) {
                    showKtxMileageOverlay(
                        "쌓인 마일리지가 ${mileage}점 있어요.\n이번 결제에 사용할까요?"
                    )
                    val mileageResult = waitForGuideAction()   // null=취소, "use", "skip"
                    removeKtxOverlay()
                    delay(500)
                    if (mileageResult == null) {
                        Log.d("KtxFlow", "마일리지 단계에서 취소함")
                        return@launch
                    }
                    if (mileageResult == "use") {
                        val useClicked = clickById("com.korail.talk:id/ktx_use_button") || clickByText("사용")
                        if (useClicked) {
                            delay(1500)
                            debugDumpScreen("마일리지 사용 클릭 후")   // 전액적용 버튼 ID/문구 확인용
                            if (!clickByText("전액적용", "전액 적용", "전액")) {
                                Log.w("KtxFlow", "전액적용 버튼 못 찾음 (위 덤프 확인 필요)")
                            }
                            delay(1200)
                            Log.d("KtxFlow", "마일리지 전액적용 시도 완료")
                        } else {
                            Log.w("KtxFlow", "마일리지 '사용' 버튼 못 찾음 — 건너뜀")
                        }
                    }
                }

                // ===== 12) 맨 아래로 스크롤 =====
                delay(500)
                scrollToBottom()
                delay(800)
                debugDumpScreen("결제 직전 최종 화면")

                // ===== 13) 최종 안내 (3초 후 자동 제거) =====
                showKtxDoneOverlay()
                delay(3000)
                removeKtxOverlay()

            } catch (e: Exception) {
                Log.e("KtxFlow", "코루틴 예외", e)
                Toast.makeText(applicationContext, "에러: ${e.message}", Toast.LENGTH_LONG).show()
                removeKtxOverlay()
            }
        }
    }

    // ============================================
    // 🌟 역 선택 흐름
    // ============================================
    private suspend fun selectStation(stationName: String, isDeparture: Boolean): Boolean {
        val fieldId = if (isDeparture) ID_DEPARTURE_STATION else ID_ARRIVAL_STATION
        val label = if (isDeparture) "출발역" else "도착역"

        updateKtxQuestion("'$stationName'(으)로 설정 중...")
        hideInputRow()
        delay(1500)

        debugDumpScreen("[$label] 클릭 직전 화면")

        Log.d("KtxFlow", "[$label] 영역 클릭 시도")
        if (!clickById(fieldId)) {
            Log.e("KtxFlow", "[$label] 영역 클릭 실패")
            return false
        }
        delay(800)

        val editNode = findFirstEditable()
        if (editNode == null) {
            Log.e("KtxFlow", "[$label] EditText 못 찾음")
            return false
        }
        Log.d("KtxFlow", "[$label] EditText 찾음, 클릭")
        editNode.performClickRecursive()
        delay(300)

        Log.d("KtxFlow", "[$label] 텍스트 입력: $stationName")
        val setOk = setEditTextValue(editNode, stationName)
        Log.d("KtxFlow", "[$label] setText 결과: $setOk")
        delay(300)

        val searchClicked = clickSearchButton()
        Log.d("KtxFlow", "[$label] 검색 버튼 클릭: $searchClicked")
        delay(800)

        debugDumpScreen("[$label] 검색 결과 화면")

        val target = findStationInList(stationName)
        if (target == null) {
            Log.e("KtxFlow", "[$label] '$stationName' 못 찾음 (stationNameTxt)")
            return false
        }

        val targetRect = Rect()
        target.getBoundsInScreen(targetRect)
        Log.d("KtxFlow", "[$label] 클릭 대상: bounds=$targetRect clickable=${target.isClickable}")

        Log.d("KtxFlow", "[$label] 결과 클릭")
        val ok = target.performClickRecursive()
        Log.d("KtxFlow", "[$label] 클릭 결과: $ok")

        if (!ok) {
            Log.e("KtxFlow", "[$label] 클릭 실패")
            return false
        }

        Log.d("KtxFlow", "[$label] 메인 복귀 대기 중...")
        for (i in 1..10) {
            delay(500)
            if (findById(ID_DEPARTURE_STATION) != null && findById(ID_GOING_DATE) != null) {
                Log.d("KtxFlow", "[$label] 메인 복귀 확인 (${i * 500}ms)")
                delay(500)
                return true
            }
        }
        Log.w("KtxFlow", "[$label] 메인 복귀 안 됨")
        return true
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun fail(msg: String) {
        Log.e("KtxFlow", "실패: $msg")
        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        removeKtxOverlay()
    }

    // ============================================
    // 🌟 입력/응답 대기
    // ============================================
    private suspend fun waitForInput(): String? {
        ktxInputDeferred = CompletableDeferred()
        val result = ktxInputDeferred!!.await()
        return if (result == "cancel") null else result
    }

    private suspend fun waitForTripType(): String {
        ktxInputDeferred = CompletableDeferred()
        return ktxInputDeferred!!.await()
    }

    private suspend fun waitForGuideAction(): String? {
        ktxInputDeferred = CompletableDeferred()
        val result = ktxInputDeferred!!.await()
        return if (result == "cancel") null else result
    }

    // ============================================
    // 🌟 안내 오버레이 (코레일톡 터치 가능)
    // ============================================
    private fun showKtxGuideOverlay(
        message: String,
        showHelpButton: Boolean = false,
        topPosition: Boolean = false
    ) {
        removeKtxOverlay()
        val ctx: Context = this

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(THEME_BACKGROUND))
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(16f).toInt())
        }

        val questionView = TextView(ctx).apply {
            text = "🤖  $message"
            setTextColor(Color.parseColor(TEXT_DARK))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BUBBLE_BOT_BG))
                cornerRadius = dp(16f)
            }
            setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())
        }
        wrapper.addView(questionView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        ktxQuestionView = questionView

        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        if (showHelpButton) {
            val helpBtn = Button(ctx).apply {
                text = "도움"
                setTextColor(Color.parseColor(THEME_PURPLE))
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    setColor(Color.WHITE); cornerRadius = dp(16f)
                    setStroke(dp(1.5f).toInt(), Color.parseColor(THEME_PURPLE))
                }
                stateListAnimator = null
                setOnClickListener { ktxInputDeferred?.complete("help") }
            }
            btnRow.addView(helpBtn, LinearLayout.LayoutParams(0, dp(44f).toInt(), 1f).apply {
                setMargins(dp(4f).toInt(), 0, dp(4f).toInt(), 0)
            })
        }

        val cancelBtn = Button(ctx).apply {
            text = "취소"
            setTextColor(Color.parseColor(CANCEL_RED))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(16f)
                setStroke(dp(1.5f).toInt(), Color.parseColor(CANCEL_RED))
            }
            stateListAnimator = null
            setOnClickListener { ktxInputDeferred?.complete("cancel") }
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(0, dp(44f).toInt(), 1f).apply {
            setMargins(dp(4f).toInt(), 0, dp(4f).toInt(), 0)
        })

        wrapper.addView(btnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(10f).toInt(), 0, 0) })

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (topPosition) Gravity.TOP else Gravity.BOTTOM
        }
        overlayView = wrapper
        windowManager?.addView(overlayView, windowParams)
    }


    // ============================================
    // 🌟 좌석 선택 옵션 오버레이
    // ============================================
    private fun showKtxSeatOptionOverlay() {
        removeKtxOverlay()
        val ctx: Context = this

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(THEME_BACKGROUND))
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(16f).toInt())
        }

        val questionView = TextView(ctx).apply {
            text = "🤖  선택하신 열차로 진행할게요!\n좌석은 어떻게 하시겠어요?"
            setTextColor(Color.parseColor(TEXT_DARK))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BUBBLE_BOT_BG))
                cornerRadius = dp(16f)
            }
            setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())
        }
        wrapper.addView(questionView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        ktxQuestionView = questionView

        val selectSeatBtn = Button(ctx).apply {
            text = "🪑  좌석 선택하기"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(THEME_PURPLE)); cornerRadius = dp(24f)
            }
            stateListAnimator = null
            setOnClickListener { ktxInputDeferred?.complete("select_seat") }
        }
        wrapper.addView(selectSeatBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50f).toInt()
        ).apply { setMargins(0, dp(12f).toInt(), 0, 0) })

        val anySeatBtn = Button(ctx).apply {
            text = "🎲  아무 좌석에 앉기"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(AUTO_GREEN)); cornerRadius = dp(24f)
            }
            stateListAnimator = null
            setOnClickListener { ktxInputDeferred?.complete("any_seat") }
        }
        wrapper.addView(anySeatBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50f).toInt()
        ).apply { setMargins(0, dp(8f).toInt(), 0, 0) })

        val cancelBtn = Button(ctx).apply {
            text = "취소"
            setTextColor(Color.parseColor(CANCEL_RED))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(16f)
                setStroke(dp(1.5f).toInt(), Color.parseColor(CANCEL_RED))
            }
            stateListAnimator = null
            setOnClickListener { ktxInputDeferred?.complete("cancel") }
        }
        wrapper.addView(cancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44f).toInt()
        ).apply { setMargins(0, dp(8f).toInt(), 0, 0) })

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        overlayView = wrapper
        windowManager?.addView(overlayView, windowParams)
    }

    // ============================================
    // 🌟 결제 정보 확인 오버레이 (네 / 취소)
    // ============================================
    private fun showKtxConfirmOverlay(message: String) {
        removeKtxOverlay()
        val ctx: Context = this

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(THEME_BACKGROUND))
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(16f).toInt())
        }

        val questionView = TextView(ctx).apply {
            text = "🤖  $message"
            setTextColor(Color.parseColor(TEXT_DARK))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BUBBLE_BOT_BG))
                cornerRadius = dp(16f)
            }
            setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())
        }
        wrapper.addView(questionView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        ktxQuestionView = questionView

        val confirmBtn = Button(ctx).apply {
            text = "✓  네, 맞아요"
            setTextColor(Color.WHITE); textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(THEME_PURPLE)); cornerRadius = dp(24f)
            }
            stateListAnimator = null
            setOnClickListener { ktxInputDeferred?.complete("confirm") }
        }
        wrapper.addView(confirmBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52f).toInt()
        ).apply { setMargins(0, dp(12f).toInt(), 0, 0) })

        val cancelBtn = Button(ctx).apply {
            text = "취소"; setTextColor(Color.parseColor(CANCEL_RED))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(16f)
                setStroke(dp(1.5f).toInt(), Color.parseColor(CANCEL_RED))
            }
            stateListAnimator = null
            setOnClickListener { ktxInputDeferred?.complete("cancel") }
        }
        wrapper.addView(cancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44f).toInt()
        ).apply { setMargins(0, dp(8f).toInt(), 0, 0) })

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        overlayView = wrapper
        windowManager?.addView(overlayView, windowParams)
    }

    // ============================================
    // 🌟 마일리지 사용 여부 오버레이 (사용 / 그냥결제 / 취소)
    // ============================================
    private fun showKtxMileageOverlay(message: String) {
        removeKtxOverlay()
        val ctx: Context = this

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(THEME_BACKGROUND))
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(16f).toInt())
        }

        val questionView = TextView(ctx).apply {
            text = "🤖  $message"
            setTextColor(Color.parseColor(TEXT_DARK)); textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BUBBLE_BOT_BG)); cornerRadius = dp(16f)
            }
            setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())
        }
        wrapper.addView(questionView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        ktxQuestionView = questionView

        val useBtn = Button(ctx).apply {
            text = "💰  네, 사용할게요"
            setTextColor(Color.WHITE); textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(AUTO_GREEN)); cornerRadius = dp(24f)
            }
            stateListAnimator = null
            setOnClickListener { ktxInputDeferred?.complete("use") }
        }
        wrapper.addView(useBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50f).toInt()
        ).apply { setMargins(0, dp(12f).toInt(), 0, 0) })

        val skipBtn = Button(ctx).apply {
            text = "아니요, 그냥 결제할게요"
            setTextColor(Color.parseColor(THEME_PURPLE)); textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(24f)
                setStroke(dp(1.5f).toInt(), Color.parseColor(THEME_PURPLE))
            }
            stateListAnimator = null
            setOnClickListener { ktxInputDeferred?.complete("skip") }
        }
        wrapper.addView(skipBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48f).toInt()
        ).apply { setMargins(0, dp(8f).toInt(), 0, 0) })

        val cancelBtn = Button(ctx).apply {
            text = "취소"; setTextColor(Color.parseColor(CANCEL_RED))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(16f)
                setStroke(dp(1.5f).toInt(), Color.parseColor(CANCEL_RED))
            }
            stateListAnimator = null
            setOnClickListener { ktxInputDeferred?.complete("cancel") }
        }
        wrapper.addView(cancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44f).toInt()
        ).apply { setMargins(0, dp(8f).toInt(), 0, 0) })

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        overlayView = wrapper
        windowManager?.addView(overlayView, windowParams)
    }

    // ============================================
    // 🌟 최종 안내 오버레이 (버튼 없음, 자동 제거)
    // ============================================
    private fun showKtxDoneOverlay() {
        removeKtxOverlay()
        val ctx: Context = this

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(THEME_BACKGROUND))
            setPadding(dp(16f).toInt(), dp(16f).toInt(), dp(16f).toInt(), dp(16f).toInt())
        }

        val questionView = TextView(ctx).apply {
            text = "🤖  거의 다 됐어요!\n결제 수단을 고르고\n맨 아래 '결제/발권' 버튼을 누르시면 완료됩니다!"
            setTextColor(Color.parseColor(TEXT_DARK)); textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BUBBLE_BOT_BG)); cornerRadius = dp(16f)
            }
            setPadding(dp(14f).toInt(), dp(14f).toInt(), dp(14f).toInt(), dp(14f).toInt())
        }
        wrapper.addView(questionView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        ktxQuestionView = questionView

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        overlayView = wrapper
        windowManager?.addView(overlayView, windowParams)
    }

    // ============================================
    // 🌟 KTX 입력 팝업 (역 입력용)
    // ============================================
    private fun showKtxInputOverlay(initialQuestion: String) {
        removeKtxOverlay()
        val ctx: Context = this

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(THEME_BACKGROUND))
            setPadding(dp(16f).toInt(), dp(14f).toInt(), dp(16f).toInt(), dp(16f).toInt())
        }

        val questionView = TextView(ctx).apply {
            text = "🤖  $initialQuestion"
            setTextColor(Color.parseColor(TEXT_DARK))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BUBBLE_BOT_BG))
                cornerRadius = dp(16f)
            }
            setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())
        }
        wrapper.addView(questionView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        ktxQuestionView = questionView

        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ktxInputRow = inputRow

        val micBtn = TextView(ctx).apply {
            text = "🎤"; textSize = 18f; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor(THEME_PURPLE)); cornerRadius = dp(22f)
            }
            setOnClickListener { onKtxMicClick() }
        }
        ktxMicView = micBtn
        inputRow.addView(micBtn, LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt()))

        val edit = EditText(ctx).apply {
            hint = "입력 후 ↑"
            setHintTextColor(Color.parseColor(TEXT_GRAY))
            setTextColor(Color.parseColor(TEXT_DARK))
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(20f)
            }
            setPadding(dp(14f).toInt(), dp(6f).toInt(), dp(14f).toInt(), dp(6f).toInt())
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { onKtxSendClick(); true } else false
            }
        }
        ktxInputView = edit
        inputRow.addView(edit, LinearLayout.LayoutParams(0, dp(40f).toInt(), 1f).apply {
            setMargins(dp(8f).toInt(), 0, dp(8f).toInt(), 0)
        })

        val sendBtn = TextView(ctx).apply {
            text = "↑"; setTextColor(Color.WHITE); textSize = 18f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor(THEME_PURPLE)); cornerRadius = dp(22f)
            }
            setOnClickListener { onKtxSendClick() }
        }
        inputRow.addView(sendBtn, LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt()))

        wrapper.addView(inputRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(10f).toInt(), 0, 0) })

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        ktxButtonRow = btnRow

        val onewayBtn = Button(ctx).apply {
            text = "편도"; setTextColor(Color.WHITE); textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(AUTO_GREEN)); cornerRadius = dp(22f)
            }
            stateListAnimator = null
            setOnClickListener { ktxInputDeferred?.complete("oneway") }
        }
        val roundBtn = Button(ctx).apply {
            text = "왕복"; setTextColor(Color.WHITE); textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(THEME_PURPLE)); cornerRadius = dp(22f)
            }
            stateListAnimator = null
            setOnClickListener { ktxInputDeferred?.complete("round") }
        }
        val btnLp = LinearLayout.LayoutParams(0, dp(44f).toInt(), 1f).apply {
            setMargins(dp(4f).toInt(), 0, dp(4f).toInt(), 0)
        }
        btnRow.addView(onewayBtn, btnLp)
        btnRow.addView(roundBtn, btnLp)
        wrapper.addView(btnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(10f).toInt(), 0, 0) })

        val cancelBtn = Button(ctx).apply {
            text = "취소"; setTextColor(Color.parseColor(CANCEL_RED))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(16f)
                setStroke(dp(1.5f).toInt(), Color.parseColor(CANCEL_RED))
            }
            stateListAnimator = null
            setOnClickListener { ktxInputDeferred?.complete("cancel") }
        }
        wrapper.addView(cancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(40f).toInt()
        ).apply { setMargins(0, dp(8f).toInt(), 0, 0) })

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        overlayView = wrapper
        windowManager?.addView(overlayView, windowParams)

        wrapper.viewTreeObserver.addOnGlobalLayoutListener {
            val view = overlayView ?: return@addOnGlobalLayoutListener
            val rect = Rect()
            view.getWindowVisibleDisplayFrame(rect)
            val screenHeight = resources.displayMetrics.heightPixels
            val keypadHeight = screenHeight - rect.bottom
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@addOnGlobalLayoutListener
            val targetY = if (keypadHeight > screenHeight * 0.15) keypadHeight else 0
            if (params.y != targetY) {
                params.y = targetY
                try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) {}
            }
        }
    }

    private fun updateKtxQuestion(question: String) {
        ktxQuestionView?.text = "🤖  $question"
        ktxInputView?.setText("")
    }

    private fun hideInputRow() {
        try {
            ktxInputRow?.visibility = View.GONE
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            ktxInputView?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
            ktxInputView?.clearFocus()
        } catch (e: Exception) {
            Log.e("KtxFlow", "hideInputRow 에러: ${e.message}")
        }
    }

    private fun showInputRow() { ktxInputRow?.visibility = View.VISIBLE }
    private fun showTripTypeButtons() {
        ktxInputRow?.visibility = View.GONE
        ktxButtonRow?.visibility = View.VISIBLE
    }
    private fun hideTripTypeButtons() { ktxButtonRow?.visibility = View.GONE }

    private fun onKtxSendClick() {
        val text = ktxInputView?.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) return
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            ktxInputView?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
            ktxInputView?.clearFocus()
        } catch (_: Exception) {}
        ktxInputDeferred?.complete(text)
    }

    private fun onKtxMicClick() {
        if (ktxIsListening) {
            ktxRecognizer?.stopListening()
            ktxIsListening = false; updateMicColor(); return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "마이크 권한이 필요해요.", Toast.LENGTH_LONG).show()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "음성 인식 미지원", Toast.LENGTH_SHORT).show()
            return
        }
        startKtxListening()
    }

    private fun startKtxListening() {
        ktxRecognizer?.destroy()
        ktxRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        ktxRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { ktxIsListening = true; updateMicColor() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { ktxIsListening = false; updateMicColor() }
            override fun onError(error: Int) {
                ktxIsListening = false; updateMicColor()
                Toast.makeText(this@AutoAgentService, "음성 인식 오류", Toast.LENGTH_SHORT).show()
            }
            override fun onResults(results: Bundle?) {
                ktxIsListening = false; updateMicColor()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    ktxInputView?.setText(matches[0])
                    onKtxSendClick()
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        ktxRecognizer?.startListening(intent)
    }

    private fun updateMicColor() {
        ktxMicView?.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (ktxIsListening) CANCEL_RED else THEME_PURPLE))
            cornerRadius = dp(22f)
        }
    }

    private fun removeKtxOverlay() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            overlayView = null
        }
        ktxQuestionView = null; ktxInputView = null; ktxMicView = null
        ktxButtonRow = null; ktxInputRow = null
        ktxRecognizer?.destroy(); ktxRecognizer = null
    }

    // ============================================
    // 이하 쿠팡 자동화 (변경 없음)
    // ============================================

    private fun runCoupangAutomation(query: String, autoMode: Boolean = false) {
        automationJob?.cancel()
        currentQuery = query
        automationJob = serviceScope.launch(Dispatchers.IO) {
            val encodedQuery = Uri.encode(query)
            val uri = Uri.parse("coupang://search?q=$encodedQuery")
            val launchIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { startActivity(launchIntent) }
            catch (e: Exception) {
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://m.coupang.com/nm/search?q=$encodedQuery")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(fallback)
            }

            var currentState = if (autoMode) AgentState.AUTO_WAIT_RESULTS else AgentState.WAIT_FOR_RESULTS_LOAD
            var stateWaitCount = 0

            for (i in 1..180) {
                delay(1000)
                val rootNode = rootInActiveWindow ?: continue
                val allNodes = rootNode.findAllNodes()

                when (currentState) {
                    AgentState.WAIT_FOR_RESULTS_LOAD -> {
                        val isLoaded = allNodes.any {
                            val t = it.text?.toString() ?: ""
                            t.contains("필터") || t.contains("원")
                        }
                        if (isLoaded && stateWaitCount > 2) {
                            currentState = AgentState.WAIT_USER_PICK; stateWaitCount = 0
                        } else stateWaitCount++
                    }
                    AgentState.WAIT_USER_PICK -> {
                        val action = showPickOverlayAndDetect()
                        when (action) {
                            "cancel" -> currentState = AgentState.CANCELLED
                            "help" -> withContext(Dispatchers.Main) {
                                Toast.makeText(applicationContext, "도움 기능은 준비 중입니다.", Toast.LENGTH_SHORT).show()
                            }
                            "auto" -> {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(applicationContext, "괜찮은 상품을 골라드릴게요...", Toast.LENGTH_SHORT).show()
                                }
                                currentState = AgentState.AUTO_CLICK_FIRST_ITEM; stateWaitCount = 0
                            }
                            "detected" -> { currentState = AgentState.WAIT_USER_BUY_DECISION; stateWaitCount = 0 }
                            else -> currentState = AgentState.CANCELLED
                        }
                    }
                    AgentState.WAIT_USER_BUY_DECISION -> {
                        val action = showBuyOverlay()
                        when (action) {
                            "cancel" -> currentState = AgentState.CANCELLED
                            "help" -> withContext(Dispatchers.Main) {
                                Toast.makeText(applicationContext, "도움 기능은 준비 중입니다.", Toast.LENGTH_SHORT).show()
                            }
                            "buy" -> { currentState = AgentState.CLICK_BUY_NOW; stateWaitCount = 0 }
                            else -> currentState = AgentState.CANCELLED
                        }
                    }
                    AgentState.AUTO_WAIT_RESULTS -> {
                        val isLoaded = allNodes.any {
                            val t = it.text?.toString() ?: ""
                            t.contains("필터") || t.contains("원")
                        }
                        if (isLoaded && stateWaitCount > 2) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(applicationContext, "괜찮은 상품을 골라드릴게요...", Toast.LENGTH_SHORT).show()
                            }
                            currentState = AgentState.AUTO_CLICK_FIRST_ITEM; stateWaitCount = 0
                        } else stateWaitCount++
                    }
                    AgentState.AUTO_CLICK_FIRST_ITEM -> {
                        val firstProduct = findFirstGoodProduct(allNodes)
                        if (firstProduct != null && firstProduct.performClickRecursive()) {
                            delay(2500); currentState = AgentState.AUTO_WAIT_DETAIL; stateWaitCount = 0
                        } else {
                            stateWaitCount++
                            if (stateWaitCount > 5) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(applicationContext, "상품을 찾지 못했어요. 직접 골라주세요.", Toast.LENGTH_LONG).show()
                                }
                                currentState = AgentState.WAIT_USER_PICK
                            }
                        }
                    }
                    AgentState.AUTO_WAIT_DETAIL -> {
                        val isDetail = allNodes.any {
                            val t = it.text?.toString() ?: ""
                            t.contains("바로구매") || t.contains("구매하기")
                        }
                        if (isDetail) { currentState = AgentState.CLICK_BUY_NOW; stateWaitCount = 0 }
                        else {
                            stateWaitCount++
                            if (stateWaitCount > 10) currentState = AgentState.CANCELLED
                        }
                    }
                    AgentState.CLICK_BUY_NOW -> {
                        val targetBuyBtn = allNodes.filter {
                            val t = it.text?.toString() ?: ""
                            val d = it.contentDescription?.toString() ?: ""
                            t.contains("구매하기") || t.contains("바로구매") || d.contains("구매하기")
                        }.maxByOrNull {
                            val rect = Rect(); it.getBoundsInScreen(rect); rect.top
                        }
                        if (targetBuyBtn?.performClickRecursive() == true) {
                            delay(3000); currentState = AgentState.WAIT_FOR_PAYMENT_PAGE; stateWaitCount = 0
                        } else {
                            stateWaitCount++
                            if (stateWaitCount > 5) currentState = AgentState.CANCELLED
                        }
                    }
                    AgentState.WAIT_FOR_PAYMENT_PAGE -> {
                        // 결제 버튼(클릭형) 또는 "밀어서 결제"(슬라이드형) 둘 다 감지
                        val payBtn = allNodes.find {
                            val t = it.text?.toString() ?: ""
                            val d = it.contentDescription?.toString() ?: ""
                            t.contains("결제하기") || t.contains("주문하기") || d.contains("결제하기")
                        }
                        val slider = allNodes.find {
                            val t = it.text?.toString() ?: ""
                            val d = it.contentDescription?.toString() ?: ""
                            t.contains("밀어") || d.contains("밀어")
                        }
                        if (payBtn != null || slider != null) {
                            val price = extractPrice(allNodes)
                            val address = extractDeliveryAddress(allNodes)
                            val payment = extractPaymentMethod(allNodes)
                            val confirmed = showFinalConfirmOverlay(
                                productName = currentQuery, price = price, address = address, payment = payment
                            )
                            if (confirmed) {
                                // 🌟 "밀어서 결제" 슬라이더가 있으면 swipe, 아니면 기존 클릭
                                val acted = if (slider != null) {
                                    val ok = slideToPay(allNodes)
                                    if (!ok) payBtn?.performClickRecursive() ?: false else true
                                } else {
                                    payBtn?.performClickRecursive() ?: false
                                }
                                Log.d("Coupang", "결제 실행: $acted (slider=${slider != null})")
                                currentState = AgentState.DONE
                            } else {
                                currentState = AgentState.CANCELLED
                            }
                        } else {
                            stateWaitCount++
                            if (stateWaitCount > 15) currentState = AgentState.CANCELLED
                        }
                    }
                    AgentState.DONE -> {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(applicationContext, "결제를 진행했습니다!", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    AgentState.CANCELLED -> {
                        withContext(Dispatchers.Main) {
                            removeOverlay()
                            Toast.makeText(applicationContext, "취소되었습니다.", Toast.LENGTH_LONG).show()
                        }
                        val backIntent = packageManager.getLaunchIntentForPackage(packageName)
                        backIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(backIntent)
                        return@launch
                    }
                }
            }
            withContext(Dispatchers.Main) {
                removeOverlay()
                Toast.makeText(applicationContext, "시간이 초과되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun findFirstGoodProduct(allNodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val candidates = allNodes.filter { node ->
            val t = node.text?.toString() ?: ""
            val d = node.contentDescription?.toString() ?: ""
            val combined = t + d
            combined.length in 10..100 && !combined.contains("광고") && !combined.contains("Sponsored") &&
                    !combined.contains("필터") && !combined.contains("최근 검색") &&
                    !combined.contains("카테고리") && !combined.contains("장바구니")
        }
        return candidates.minByOrNull {
            val rect = Rect(); it.getBoundsInScreen(rect); rect.top
        }
    }

    private fun extractPrice(allNodes: List<AccessibilityNodeInfo>): String {
        val priceTexts = allNodes.mapNotNull { it.text?.toString() }
            .filter { it.contains("원") && it.any { ch -> ch.isDigit() } }
        val maxPrice = priceTexts.maxByOrNull {
            it.filter { ch -> ch.isDigit() }.toLongOrNull() ?: 0L
        }
        return maxPrice ?: "확인 불가"
    }

    private fun collectTextsNearLabel(
        allNodes: List<AccessibilityNodeInfo>, labelKeywords: List<String>
    ): List<String> {
        val labelNode = allNodes.firstOrNull { node ->
            val t = node.text?.toString() ?: ""
            (labelKeywords.any { k -> t == k || t.startsWith(k) }) && t.length < 15
        } ?: return emptyList()
        val labelRect = Rect()
        labelNode.getBoundsInScreen(labelRect)
        val candidates = allNodes.mapNotNull { node ->
            val t = node.text?.toString() ?: ""
            if (t.isBlank() || t == labelNode.text?.toString()) return@mapNotNull null
            if (labelKeywords.any { t == it }) return@mapNotNull null
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val isRightOf = rect.left >= labelRect.right - 10 &&
                    Math.abs(rect.centerY() - labelRect.centerY()) < 50
            val isBelow = rect.top in (labelRect.bottom)..(labelRect.bottom + 400) &&
                    rect.left < labelRect.right + 200
            if (isRightOf || isBelow) {
                val distance = if (isRightOf) rect.left - labelRect.right else rect.top - labelRect.bottom
                Pair(t, distance)
            } else null
        }
        return candidates.sortedBy { it.second }.map { it.first }
    }

    private fun extractDeliveryAddress(allNodes: List<AccessibilityNodeInfo>): String {
        val allTexts = allNodes.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotBlank() }

        // 🌟 한국 주소 패턴 직접 매칭 (라벨 위치에 의존하지 않음)
        //    "인천광역시 미추홀구 ...", "서울특별시 강남구 ...", "경기도 ○○시 ..." 등
        val addrRegex = Regex("(특별시|광역시|특별자치시|특별자치도|[가-힣]+도|[가-힣]+시)\\s*[가-힣]+(구|군|시|읍|면)")
        val byPattern = allTexts.firstOrNull { t ->
            addrRegex.containsMatchIn(t) && t.length in 6..90 &&
                    !t.contains("배송비") && !t.contains("쿠폰") && !t.contains("원")
        }
        if (byPattern != null) return byPattern

        // fallback: 기존 라벨 근처 방식
        val candidates = collectTextsNearLabel(allNodes, listOf("배송지", "받는사람", "받는 사람", "배송 주소", "주소"))
        val realAddress = candidates.firstOrNull { text ->
            addrRegex.containsMatchIn(text) && text.length in 6..90 &&
                    !text.contains("배송비") && !text.contains("쿠폰")
        }
        return realAddress ?: candidates.firstOrNull() ?: "확인 불가"
    }

    private fun extractPaymentMethod(allNodes: List<AccessibilityNodeInfo>): String {
        val allTexts = allNodes.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotBlank() }

        // 🌟 결제수단 직접 매칭
        //    1순위: "농협은행 / ****9943" 처럼 은행/카드명 + 마스킹 숫자
        val maskedRegex = Regex("\\*{2,}\\d{2,}")   // ****9943
        val byMasked = allTexts.firstOrNull { t ->
            t.length < 40 && maskedRegex.containsMatchIn(t) &&
                    !t.contains("원") && !t.contains("소득공제") && !t.contains("현금영수증")
        }
        if (byMasked != null) return byMasked

        // 2순위: 결제수단 키워드가 들어간 짧은 텍스트
        val paymentKeywords = listOf("쿠페이", "카카오페이", "네이버페이", "토스", "카드", "계좌이체",
            "무통장", "은행", "휴대폰", "페이코", "삼성페이", "애플페이", "페이")
        val byKeyword = allTexts.firstOrNull { t ->
            t.length < 30 && paymentKeywords.any { k -> t.contains(k) } &&
                    !t.contains("결제하기") && !t.contains("결제수단") &&
                    !t.contains("결제금액") && !t.contains("원") && !t.contains("동의")
        }
        if (byKeyword != null) return byKeyword

        // fallback: 기존 라벨 근처 방식
        val candidates = collectTextsNearLabel(allNodes, listOf("결제수단", "결제 수단", "결제방법", "결제 방법"))
        return candidates.firstOrNull { text ->
            !text.contains("원") && !text.contains("금액") && !text.contains("결제하기") &&
                    text.length in 2..30
        } ?: "확인 불가"
    }

    private suspend fun showPickOverlayAndDetect(): String {
        userActionDeferred = CompletableDeferred()
        withContext(Dispatchers.Main) {
            showChatOverlay(message = "원하는 물품을 클릭해주세요",
                showBigBuyButton = false, showAutoButton = true)
        }
        val detectJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(700)
                val rootNode = rootInActiveWindow ?: continue
                val allNodes = rootNode.findAllNodes()
                val isDetail = allNodes.any {
                    val t = it.text?.toString() ?: ""
                    val d = it.contentDescription?.toString() ?: ""
                    (t.contains("바로구매") || t.contains("구매하기") ||
                            d.contains("바로구매") || d.contains("구매하기"))
                }
                if (isDetail) { userActionDeferred?.complete("detected"); break }
            }
        }
        val result = userActionDeferred!!.await()
        detectJob.cancel()
        withContext(Dispatchers.Main) { removeOverlay() }
        return result
    }

    private suspend fun showBuyOverlay(): String {
        userActionDeferred = CompletableDeferred()
        withContext(Dispatchers.Main) {
            showChatOverlay(message = "상품 상세설명을 읽어보세요",
                showBigBuyButton = true, showAutoButton = false)
        }
        val result = userActionDeferred!!.await()
        withContext(Dispatchers.Main) { removeOverlay() }
        return result
    }

    private suspend fun showFinalConfirmOverlay(
        productName: String, price: String, address: String, payment: String
    ): Boolean {
        userActionDeferred = CompletableDeferred()
        withContext(Dispatchers.Main) {
            showFinalConfirmUI(productName, price, address, payment)
        }
        val result = userActionDeferred!!.await()
        withContext(Dispatchers.Main) { removeOverlay() }
        return result == "buy"
    }

    private fun showFinalConfirmUI(productName: String, price: String, address: String, payment: String) {
        removeOverlay()
        val ctx: Context = this
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(THEME_BACKGROUND))
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(16f).toInt())
        }
        val titleBubble = TextView(ctx).apply {
            text = "🤖  마지막으로 확인해주세요!"
            setTextColor(Color.parseColor(TEXT_DARK)); textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BUBBLE_BOT_BG)); cornerRadius = dp(16f)
            }
            setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())
        }
        wrapper.addView(titleBubble, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val infoCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BUBBLE_BOT_BG)); cornerRadius = dp(16f)
            }
            setPadding(dp(16f).toInt(), dp(14f).toInt(), dp(16f).toInt(), dp(14f).toInt())
        }
        infoCard.addView(makeInfoRow("📦  상품", productName))
        infoCard.addView(makeDivider())
        infoCard.addView(makeInfoRow("💰  금액", price))
        infoCard.addView(makeDivider())
        infoCard.addView(makeInfoRow("🏠  배송지", address))
        infoCard.addView(makeDivider())
        infoCard.addView(makeInfoRow("💳  결제", payment))
        wrapper.addView(infoCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(8f).toInt(), 0, 0) })
        val buyBtn = Button(ctx).apply {
            text = "결제하기"; setTextColor(Color.WHITE); textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(THEME_PURPLE)); cornerRadius = dp(24f)
            }
            stateListAnimator = null
            setOnClickListener { userActionDeferred?.complete("buy") }
        }
        wrapper.addView(buyBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56f).toInt()
        ).apply { setMargins(0, dp(12f).toInt(), 0, 0) })
        val cancelBtn = Button(ctx).apply {
            text = "취소"; setTextColor(Color.parseColor(CANCEL_RED))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(16f)
                setStroke(dp(1.5f).toInt(), Color.parseColor(CANCEL_RED))
            }
            stateListAnimator = null
            setOnClickListener { userActionDeferred?.complete("cancel") }
        }
        wrapper.addView(cancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48f).toInt()
        ).apply { setMargins(0, dp(8f).toInt(), 0, 0) })
        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        overlayView = wrapper
        windowManager?.addView(overlayView, windowParams)
    }

    private fun makeInfoRow(label: String, value: String): LinearLayout {
        val ctx: Context = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8f).toInt(), 0, dp(8f).toInt())
        }
        row.addView(TextView(ctx).apply {
            text = label; setTextColor(Color.parseColor(TEXT_GRAY)); textSize = 13f
        })
        row.addView(TextView(ctx).apply {
            text = value; setTextColor(Color.parseColor(TEXT_DARK)); textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4f).toInt(), 0, 0)
        })
        return row
    }

    private fun makeDivider(): View {
        val divider = View(this).apply { setBackgroundColor(Color.parseColor("#EEEEEE")) }
        divider.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1f).toInt())
        return divider
    }

    private fun showChatOverlay(message: String, showBigBuyButton: Boolean, showAutoButton: Boolean = false) {
        removeOverlay()
        val ctx: Context = this
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(THEME_BACKGROUND))
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(16f).toInt())
        }
        val botBubble = TextView(ctx).apply {
            text = "🤖  $message"; setTextColor(Color.parseColor(TEXT_DARK)); textSize = 15f
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BUBBLE_BOT_BG)); cornerRadius = dp(16f)
            }
            setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())
        }
        wrapper.addView(botBubble, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        if (showAutoButton) {
            val autoBtn = Button(ctx).apply {
                text = "🎁  아무거나 골라줘"; setTextColor(Color.WHITE); textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(AUTO_GREEN)); cornerRadius = dp(24f)
                }
                stateListAnimator = null
            }
            autoBtn.setOnClickListener { userActionDeferred?.complete("auto") }
            wrapper.addView(autoBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50f).toInt()
            ).apply { setMargins(0, dp(12f).toInt(), 0, 0) })
        }
        if (showBigBuyButton) {
            val buyBtn = Button(ctx).apply {
                text = "구매하기"; setTextColor(Color.WHITE); textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(THEME_PURPLE)); cornerRadius = dp(24f)
                }
                stateListAnimator = null
            }
            buyBtn.setOnClickListener { userActionDeferred?.complete("buy") }
            wrapper.addView(buyBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52f).toInt()
            ).apply { setMargins(0, dp(12f).toInt(), 0, 0) })
        }

        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val cancelBtn = Button(ctx).apply {
            text = "취소"; setTextColor(Color.parseColor(CANCEL_RED))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(16f)
                setStroke(dp(1.5f).toInt(), Color.parseColor(CANCEL_RED))
            }
            stateListAnimator = null
        }
        cancelBtn.setOnClickListener { userActionDeferred?.complete("cancel") }
        val helpBtn = Button(ctx).apply {
            text = "도움"; setTextColor(Color.parseColor(THEME_PURPLE))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(16f)
                setStroke(dp(1.5f).toInt(), Color.parseColor(THEME_PURPLE))
            }
            stateListAnimator = null
        }
        helpBtn.setOnClickListener { userActionDeferred?.complete("help") }
        val btnParams = LinearLayout.LayoutParams(0, dp(44f).toInt(), 1f).apply {
            setMargins(dp(4f).toInt(), 0, dp(4f).toInt(), 0)
        }
        btnRow.addView(cancelBtn, btnParams)
        btnRow.addView(helpBtn, btnParams)
        wrapper.addView(btnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(10f).toInt(), 0, 0) })

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        overlayView = wrapper
        windowManager?.addView(overlayView, windowParams)
    }

    private fun removeOverlay() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            overlayView = null
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun AccessibilityNodeInfo.findAllNodes(): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf(this)
        for (i in 0 until childCount) {
            getChild(i)?.let { nodes.addAll(it.findAllNodes()) }
        }
        return nodes
    }

    private fun AccessibilityNodeInfo.performClickRecursive(): Boolean {
        var target: AccessibilityNodeInfo? = this
        var depth = 0
        while (target != null && !target.isClickable && depth < 5) {
            target = target.parent
            depth++
        }
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    // ============================================
    // 🌟 "밀어서 결제" 슬라이드 제스처
    // ============================================
    // 주어진 영역(rect)을 좌→우로 쓸어준다. (슬라이더 trackThumb 이동)
    private fun swipeRight(rect: Rect, durationMs: Long = 600L): Boolean {
        return try {
            // 시작점은 트랙 왼쪽 안쪽, 끝점은 화면 오른쪽 끝(살짝 안쪽)까지 확실히 민다
            val screenWidth = resources.displayMetrics.widthPixels
            val y = rect.centerY().toFloat()
            val startX = (rect.left + rect.width() * 0.12f)
            val endX = (screenWidth - 8f)

            val path = Path().apply {
                moveTo(startX, y)
                lineTo(endX, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val done = CompletableDeferred<Boolean>()
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { done.complete(true) }
                override fun onCancelled(d: GestureDescription?) { done.complete(false) }
            }, null)
            Log.d("Coupang", "swipeRight dispatch=$ok rect=$rect ($startX→$endX, y=$y, screenW=$screenWidth)")
            ok
        } catch (e: Exception) {
            Log.e("Coupang", "swipeRight 실패", e)
            false
        }
    }

    // "밀어서 결제" 슬라이더 노드를 찾아 그 영역을 쓸어준다
    private fun slideToPay(allNodes: List<AccessibilityNodeInfo>): Boolean {
        val slider = allNodes.firstOrNull {
            val t = it.text?.toString() ?: ""
            val d = it.contentDescription?.toString() ?: ""
            t.contains("밀어서") || d.contains("밀어서") ||
                    t.contains("밀어") || d.contains("밀어") ||
                    t.contains("slide", ignoreCase = true) || d.contains("slide", ignoreCase = true)
        } ?: return false

        val rect = Rect()
        slider.getBoundsInScreen(rect)
        Log.d("Coupang", "밀어서결제 노드 bounds=$rect text='${slider.text}' desc='${slider.contentDescription}'")
        if (rect.width() < 50) return false   // 영역이 비정상이면 포기
        return swipeRight(rect)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        serviceScope.launch(Dispatchers.Main) {
            removeOverlay(); removeKtxOverlay()
        }
    }
}