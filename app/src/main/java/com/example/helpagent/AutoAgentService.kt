package com.example.helpagent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*

class AutoAgentService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var automationJob: Job? = null

    // 🌟 오버레이(팝업) 관련 변수
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    // 코루틴 일시 정지를 위한 바구니 2개 (선택용, 결제확인용)
    private var itemSelectionDeferred: CompletableDeferred<String?>? = null
    private var userConfirmDeferred: CompletableDeferred<Boolean>? = null

    // 🌟 검색부터 결제까지 하나의 흐름으로 통합!
    enum class AgentState {
        WAIT_FOR_RESULTS_LOAD,
        EXTRACT_RESULTS_AND_SELECT, // 리스트 추출 후 팝업 띄우기
        FIND_AND_CLICK_ITEM,        // 유저가 고른 상품 클릭
        WAIT_FOR_DETAIL,            // 상세 페이지 대기
        CLICK_BUY_NOW,              // 바로구매 클릭
        WAIT_FOR_PAYMENT_PAGE,      // 결제 페이지 대기
        DONE,
        CANCELLED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AutoAgent", "서비스 연결: 더블 오버레이 논스톱 에이전트 가동")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        serviceScope.launch {
            AgentController.commandFlow.collect { command ->
                if (command.intent == "buy_product") {
                    runCoupangAutomation(command.query)
                }
            }
        }
    }

    private fun runCoupangAutomation(query: String) {
        automationJob?.cancel()
        automationJob = serviceScope.launch(Dispatchers.IO) {

            // 1. 딥링크 검색 실행
            val encodedQuery = Uri.encode(query)
            val uri = Uri.parse("coupang://search?q=$encodedQuery")
            val launchIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                val fallbackUri = Uri.parse("https://m.coupang.com/nm/search?q=$encodedQuery")
                val fallbackIntent = Intent(Intent.ACTION_VIEW, fallbackUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(fallbackIntent)
            }

            var currentState = AgentState.WAIT_FOR_RESULTS_LOAD
            var stateWaitCount = 0
            var targetProductName = "" // 유저가 팝업에서 선택한 상품명 저장용

            // 타임아웃을 넉넉히 80초로 늘림 (유저가 고민할 시간 포함)
            for (i in 1..80) {
                delay(1000)
                val rootNode = rootInActiveWindow ?: continue
                val allNodes = rootNode.findAllNodes()

                when (currentState) {
                    // [1] 결과창 로딩 대기
                    AgentState.WAIT_FOR_RESULTS_LOAD -> {
                        val isLoaded = allNodes.any {
                            val t = it.text?.toString() ?: ""
                            t.contains("필터") || t.contains("원")
                        }

                        if (isLoaded && stateWaitCount > 2) {
                            currentState = AgentState.EXTRACT_RESULTS_AND_SELECT
                            stateWaitCount = 0
                        } else {
                            stateWaitCount++
                        }
                    }

                    // [2] 🌟 4개 추출 후 '상품 선택 팝업' 띄우기!
                    AgentState.EXTRACT_RESULTS_AND_SELECT -> {
                        val allTexts = allNodes.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotBlank() }
                        val items = allTexts.filter { text ->
                            text.length > 15 &&
                                    !text.contains("도착") &&
                                    !text.contains("예정") &&
                                    !text.contains("당") &&
                                    !text.contains("원") &&
                                    !text.contains("리뷰") &&
                                    !text.contains("무료배송") &&
                                    !text.contains("로켓") &&
                                    !text.contains("최근 검색어") &&
                                    !text.contains("장바구니")
                        }.distinct().take(4)

                        if (items.isNotEmpty()) {
                            Log.d("AutoAgent", "상품 추출 성공! 선택 오버레이를 띄웁니다.")

                            // 코루틴 일시 정지 후 팝업 결과 기다림
                            val selectedItem = showSelectionOverlay(items)

                            if (selectedItem != null) {
                                Log.d("AutoAgent", "유저가 상품을 골랐습니다: $selectedItem")
                                targetProductName = selectedItem
                                currentState = AgentState.FIND_AND_CLICK_ITEM
                                stateWaitCount = 0
                            } else {
                                Log.d("AutoAgent", "유저가 상품 선택을 취소했습니다.")
                                currentState = AgentState.CANCELLED
                            }
                        }
                    }

                    // [3] 고른 상품 찾아 누르기
                    AgentState.FIND_AND_CLICK_ITEM -> {
                        val safeKeyword = if (targetProductName.length > 10) targetProductName.substring(0, 10) else targetProductName
                        val targetNode = allNodes.find {
                            val t = it.text?.toString() ?: ""
                            val d = it.contentDescription?.toString() ?: ""
                            (t.contains(safeKeyword) || d.contains(safeKeyword)) && !it.isEditable
                        }

                        if (targetNode?.performClickRecursive() == true) {
                            delay(2500)
                            currentState = AgentState.WAIT_FOR_DETAIL
                            stateWaitCount = 0
                        } else { stateWaitCount++ }
                    }

                    // [4] 상세 페이지 대기
                    AgentState.WAIT_FOR_DETAIL -> {
                        val isLoaded = allNodes.any {
                            val t = it.text?.toString() ?: ""
                            t.contains("구매하기") || t.contains("바로구매")
                        }
                        if (isLoaded && stateWaitCount > 1) {
                            currentState = AgentState.CLICK_BUY_NOW
                            stateWaitCount = 0
                        } else { stateWaitCount++ }
                    }

                    // [5] 바로구매 클릭
                    AgentState.CLICK_BUY_NOW -> {
                        val targetBuyBtn = allNodes.filter {
                            val t = it.text?.toString() ?: ""
                            val d = it.contentDescription?.toString() ?: ""
                            t.contains("구매하기") || t.contains("바로구매") || d.contains("구매하기")
                        }.maxByOrNull {
                            val rect = Rect(); it.getBoundsInScreen(rect); rect.top
                        }

                        if (targetBuyBtn?.performClickRecursive() == true) {
                            delay(3000)
                            currentState = AgentState.WAIT_FOR_PAYMENT_PAGE
                        } else { stateWaitCount++ }
                    }

                    // [6] 🌟 결제 승인 팝업 띄우기
                    AgentState.WAIT_FOR_PAYMENT_PAGE -> {
                        val payBtn = allNodes.find {
                            val t = it.text?.toString() ?: ""
                            val d = it.contentDescription?.toString() ?: ""
                            t.contains("결제하기") || t.contains("주문하기") || d.contains("결제하기")
                        }

                        if (payBtn != null) {
                            val isConfirmed = showConfirmationOverlay()

                            if (isConfirmed) {
                                payBtn.performClickRecursive()
                                currentState = AgentState.DONE
                            } else {
                                currentState = AgentState.CANCELLED
                            }
                        } else {
                            stateWaitCount++
                        }
                    }

                    AgentState.DONE -> {
                        withContext(Dispatchers.Main) { Toast.makeText(applicationContext, "성공적으로 결제를 진행했습니다!", Toast.LENGTH_SHORT).show() }
                        return@launch
                    }

                    AgentState.CANCELLED -> {
                        withContext(Dispatchers.Main) { Toast.makeText(applicationContext, "취소하였습니다.", Toast.LENGTH_LONG).show() }
                        val backIntent = packageManager.getLaunchIntentForPackage(packageName)
                        backIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(backIntent)
                        return@launch
                    }
                    else -> break
                }
            }
        }
    }

    // ==========================================
    // 🌟 오버레이 1: 상품 리스트 선택 팝업
    // ==========================================
    private suspend fun showSelectionOverlay(items: List<String>): String? {
        itemSelectionDeferred = CompletableDeferred()

        withContext(Dispatchers.Main) {
            val layout = LinearLayout(this@AutoAgentService).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#E62196F3")) // 파란색 반투명
                setPadding(40, 60, 40, 60)
                gravity = Gravity.CENTER
            }

            val title = TextView(this@AutoAgentService).apply {
                text = "🤖 찾으시는 상품을 선택해주세요"
                setTextColor(Color.WHITE)
                textSize = 18f
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(0, 0, 0, 30)
            }
            layout.addView(title)

            // 아이템이 길 수 있으므로 스크롤뷰 추가
            val scrollView = ScrollView(this@AutoAgentService)
            val btnContainer = LinearLayout(this@AutoAgentService).apply { orientation = LinearLayout.VERTICAL }

            items.forEach { product ->
                val btn = Button(this@AutoAgentService).apply {
                    text = product
                    setTextColor(Color.parseColor("#2196F3"))
                    setBackgroundColor(Color.WHITE)
                    setPadding(20, 20, 20, 20)

                    val marginParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, 20)
                    }
                    layoutParams = marginParams

                    setOnClickListener {
                        removeOverlay()
                        itemSelectionDeferred?.complete(product) // 🌟 선택한 상품명 전달
                    }
                }
                btnContainer.addView(btn)
            }

            // 취소 버튼
            val cancelBtn = Button(this@AutoAgentService).apply {
                text = "취소 (원래 앱으로 돌아가기)"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#F44336")) // 빨간색
                setOnClickListener {
                    removeOverlay()
                    itemSelectionDeferred?.complete(null) // 🌟 null 전달 시 취소 처리
                }
            }
            btnContainer.addView(cancelBtn)

            scrollView.addView(btnContainer)
            layout.addView(scrollView)

            val windowParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }

            overlayView = layout
            windowManager?.addView(overlayView, windowParams)
        }

        // 유저가 버튼을 누를 때까지 코루틴 일시 정지
        return itemSelectionDeferred!!.await()
    }

    // ==========================================
    // 🌟 오버레이 2: 최종 결제 승인 팝업
    // ==========================================
    private suspend fun showConfirmationOverlay(): Boolean {
        userConfirmDeferred = CompletableDeferred()

        withContext(Dispatchers.Main) {
            val layout = LinearLayout(this@AutoAgentService).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#E6333333")) // 반투명 검정
                setPadding(40, 60, 40, 60)
                gravity = Gravity.CENTER
            }

            val title = TextView(this@AutoAgentService).apply {
                text = "🤖 에이전트 결제 확인\n\n이 상품을 정말로 결제하시겠습니까?"
                setTextColor(Color.WHITE)
                textSize = 18f
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(0, 0, 0, 40)
            }
            layout.addView(title)

            val btnLayout = LinearLayout(this@AutoAgentService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            val btnYes = Button(this@AutoAgentService).apply {
                text = "예 (결제)"
                setBackgroundColor(Color.parseColor("#4CAF50"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    removeOverlay()
                    userConfirmDeferred?.complete(true)
                }
            }

            val btnNo = Button(this@AutoAgentService).apply {
                text = "아니오 (취소)"
                setBackgroundColor(Color.parseColor("#F44336"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    removeOverlay()
                    userConfirmDeferred?.complete(false)
                }
            }

            val param = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(20, 0, 20, 0)
            }
            btnLayout.addView(btnYes, param)
            btnLayout.addView(btnNo, param)
            layout.addView(btnLayout)

            val windowParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }

            overlayView = layout
            windowManager?.addView(overlayView, windowParams)
        }

        return userConfirmDeferred!!.await()
    }

    private fun removeOverlay() {
        overlayView?.let {
            windowManager?.removeView(it)
            overlayView = null
        }
    }

    // ==========================================
    // 유틸리티
    // ==========================================
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        serviceScope.launch(Dispatchers.Main) { removeOverlay() }
    }
}