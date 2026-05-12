package com.example.helpagent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*

class AutoAgentService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var automationJob: Job? = null

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    private var userActionDeferred: CompletableDeferred<String>? = null

    private var currentQuery: String = ""

    companion object {
        const val THEME_PURPLE = "#9C27B0"
        const val THEME_BACKGROUND = "#F0F2F5"
        const val BUBBLE_BOT_BG = "#FFFFFF"
        const val TEXT_DARK = "#222222"
        const val TEXT_GRAY = "#666666"
        const val CANCEL_RED = "#F44336"
        const val AUTO_GREEN = "#4CAF50"
    }

    enum class AgentState {
        WAIT_FOR_RESULTS_LOAD,
        WAIT_USER_PICK,
        WAIT_USER_BUY_DECISION,
        AUTO_WAIT_RESULTS,
        AUTO_CLICK_FIRST_ITEM,
        AUTO_WAIT_DETAIL,
        CLICK_BUY_NOW,
        WAIT_FOR_PAYMENT_PAGE,
        DONE,
        CANCELLED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AutoAgent", "서비스 연결됨")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        serviceScope.launch {
            AgentController.commandFlow.collect { command ->
                if (command.intent == "buy_product" || command.intent == "select_item") {
                    runCoupangAutomation(command.query, command.autoMode)
                }
            }
        }
    }

    private fun runCoupangAutomation(query: String, autoMode: Boolean = false) {
        automationJob?.cancel()
        currentQuery = query
        automationJob = serviceScope.launch(Dispatchers.IO) {

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

            var currentState = if (autoMode) {
                AgentState.AUTO_WAIT_RESULTS
            } else {
                AgentState.WAIT_FOR_RESULTS_LOAD
            }
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
                            currentState = AgentState.WAIT_USER_PICK
                            stateWaitCount = 0
                        } else {
                            stateWaitCount++
                        }
                    }

                    AgentState.WAIT_USER_PICK -> {
                        val action = showPickOverlayAndDetect()
                        when (action) {
                            "cancel" -> currentState = AgentState.CANCELLED
                            "help" -> {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(applicationContext, "도움 기능은 준비 중입니다.", Toast.LENGTH_SHORT).show()
                                }
                            }
                            "auto" -> {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(applicationContext, "괜찮은 상품을 골라드릴게요...", Toast.LENGTH_SHORT).show()
                                }
                                currentState = AgentState.AUTO_CLICK_FIRST_ITEM
                                stateWaitCount = 0
                            }
                            "detected" -> {
                                currentState = AgentState.WAIT_USER_BUY_DECISION
                                stateWaitCount = 0
                            }
                            else -> currentState = AgentState.CANCELLED
                        }
                    }

                    AgentState.WAIT_USER_BUY_DECISION -> {
                        val action = showBuyOverlay()
                        when (action) {
                            "cancel" -> currentState = AgentState.CANCELLED
                            "help" -> {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(applicationContext, "도움 기능은 준비 중입니다.", Toast.LENGTH_SHORT).show()
                                }
                            }
                            "buy" -> {
                                currentState = AgentState.CLICK_BUY_NOW
                                stateWaitCount = 0
                            }
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
                            currentState = AgentState.AUTO_CLICK_FIRST_ITEM
                            stateWaitCount = 0
                        } else {
                            stateWaitCount++
                        }
                    }

                    AgentState.AUTO_CLICK_FIRST_ITEM -> {
                        val firstProduct = findFirstGoodProduct(allNodes)

                        if (firstProduct != null && firstProduct.performClickRecursive()) {
                            delay(2500)
                            currentState = AgentState.AUTO_WAIT_DETAIL
                            stateWaitCount = 0
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
                        if (isDetail) {
                            currentState = AgentState.CLICK_BUY_NOW
                            stateWaitCount = 0
                        } else {
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
                            delay(3000)
                            currentState = AgentState.WAIT_FOR_PAYMENT_PAGE
                            stateWaitCount = 0
                        } else {
                            stateWaitCount++
                            if (stateWaitCount > 5) currentState = AgentState.CANCELLED
                        }
                    }

                    AgentState.WAIT_FOR_PAYMENT_PAGE -> {
                        val payBtn = allNodes.find {
                            val t = it.text?.toString() ?: ""
                            val d = it.contentDescription?.toString() ?: ""
                            t.contains("결제하기") || t.contains("주문하기") || d.contains("결제하기")
                        }

                        if (payBtn != null) {
                            val price = extractPrice(allNodes)
                            val address = extractDeliveryAddress(allNodes)
                            val payment = extractPaymentMethod(allNodes)

                            val confirmed = showFinalConfirmOverlay(
                                productName = currentQuery,
                                price = price,
                                address = address,
                                payment = payment
                            )

                            if (confirmed) {
                                payBtn.performClickRecursive()
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

            combined.length in 10..100 &&
                    !combined.contains("광고") &&
                    !combined.contains("Sponsored") &&
                    !combined.contains("필터") &&
                    !combined.contains("최근 검색") &&
                    !combined.contains("카테고리") &&
                    !combined.contains("장바구니")
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

    // ==========================================
    // 🌟 라벨 아래/오른쪽의 후보 텍스트들을 모두 수집
    // ==========================================
    private fun collectTextsNearLabel(
        allNodes: List<AccessibilityNodeInfo>,
        labelKeywords: List<String>
    ): List<String> {
        val labelNode = allNodes.firstOrNull { node ->
            val t = node.text?.toString() ?: ""
            (labelKeywords.any { keyword ->
                t == keyword || t.startsWith(keyword)
            }) && t.length < 15
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
                val distance = if (isRightOf) {
                    rect.left - labelRect.right
                } else {
                    rect.top - labelRect.bottom
                }
                Pair(t, distance)
            } else null
        }

        return candidates.sortedBy { it.second }.map { it.first }
    }

    // ==========================================
    // 🌟 배송지 추출: 실제 주소 패턴이 있는 텍스트 선택
    // ==========================================
    private fun extractDeliveryAddress(allNodes: List<AccessibilityNodeInfo>): String {
        val candidates = collectTextsNearLabel(
            allNodes,
            listOf("배송지", "받는사람", "받는 사람", "배송 주소", "주소")
        )

        Log.d("ExtractDebug", "배송지 후보들: $candidates")

        val addressPattern = Regex(".*(시|도)\\s?.*(구|군|읍|면).*")

        // 1순위: 한국 주소 패턴 매칭
        val realAddress = candidates.firstOrNull { text ->
            addressPattern.containsMatchIn(text) &&
                    text.length in 8..80 &&
                    !text.contains("배송비") &&
                    !text.contains("쿠폰")
        }
        if (realAddress != null) return realAddress

        // 2순위: 번지수가 포함된 텍스트
        val withNumber = candidates.firstOrNull { text ->
            text.length in 10..80 &&
                    text.any { it.isDigit() } &&
                    !text.contains("원") &&
                    !text.contains("기본") &&
                    !text.contains("배송지")
        }
        if (withNumber != null) return withNumber

        return candidates.firstOrNull() ?: "확인 불가"
    }

    // ==========================================
    // 🌟 결제수단 추출: 결제 관련 키워드가 있는 텍스트 선택
    // ==========================================
    private fun extractPaymentMethod(allNodes: List<AccessibilityNodeInfo>): String {
        val candidates = collectTextsNearLabel(
            allNodes,
            listOf("결제수단", "결제 수단", "결제방법", "결제 방법")
        )

        Log.d("ExtractDebug", "결제수단 후보들: $candidates")

        val paymentKeywords = listOf(
            "쿠페이", "카카오페이", "네이버페이", "토스",
            "카드", "계좌", "휴대폰", "페이코",
            "삼성페이", "애플페이", "현금", "페이"
        )

        // 1순위: 결제수단 키워드 포함된 짧은 텍스트
        val realPayment = candidates.firstOrNull { text ->
            text.length < 30 &&
                    paymentKeywords.any { keyword -> text.contains(keyword) } &&
                    !text.contains("결제하기") &&
                    !text.contains("결제수단") &&
                    !text.contains("결제금액") &&
                    !text.contains("원")  // 가격 제외
        }
        if (realPayment != null) return realPayment

        // 🌟 폴백: 가격/금액 관련 텍스트는 절대 제외
        val safeFallback = candidates.firstOrNull { text ->
            !text.contains("원") &&
                    !text.contains("금액") &&
                    !text.contains("결제하기") &&
                    !text.contains("결제수단") &&
                    !text.any { it.isDigit() } &&  // 숫자 없는 것만 (가격 차단)
                    text.length in 2..30
        }
        return safeFallback ?: "확인 불가"
    }

    private suspend fun showPickOverlayAndDetect(): String {
        userActionDeferred = CompletableDeferred()

        withContext(Dispatchers.Main) {
            showChatOverlay(
                message = "원하는 물품을 클릭해주세요",
                showBigBuyButton = false,
                showAutoButton = true
            )
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
                if (isDetail) {
                    userActionDeferred?.complete("detected")
                    break
                }
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
            showChatOverlay(
                message = "상품 상세설명을 읽어보세요",
                showBigBuyButton = true,
                showAutoButton = false
            )
        }

        val result = userActionDeferred!!.await()
        withContext(Dispatchers.Main) { removeOverlay() }
        return result
    }

    private suspend fun showFinalConfirmOverlay(
        productName: String,
        price: String,
        address: String,
        payment: String
    ): Boolean {
        userActionDeferred = CompletableDeferred()

        withContext(Dispatchers.Main) {
            showFinalConfirmUI(productName, price, address, payment)
        }

        val result = userActionDeferred!!.await()
        withContext(Dispatchers.Main) { removeOverlay() }
        return result == "buy"
    }

    private fun showFinalConfirmUI(
        productName: String,
        price: String,
        address: String,
        payment: String
    ) {
        removeOverlay()
        val ctx: Context = this

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(THEME_BACKGROUND))
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(16f).toInt())
        }

        val titleBubble = TextView(ctx).apply {
            text = "🤖  마지막으로 확인해주세요!"
            setTextColor(Color.parseColor(TEXT_DARK))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BUBBLE_BOT_BG))
                cornerRadius = dp(16f)
            }
            setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())
        }
        val titleParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        wrapper.addView(titleBubble, titleParams)

        val infoCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BUBBLE_BOT_BG))
                cornerRadius = dp(16f)
            }
            setPadding(dp(16f).toInt(), dp(14f).toInt(), dp(16f).toInt(), dp(14f).toInt())
        }
        val infoCardParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(8f).toInt(), 0, 0) }

        infoCard.addView(makeInfoRow("📦  상품", productName))
        infoCard.addView(makeDivider())
        infoCard.addView(makeInfoRow("💰  금액", price))
        infoCard.addView(makeDivider())
        infoCard.addView(makeInfoRow("🏠  배송지", address))
        infoCard.addView(makeDivider())
        infoCard.addView(makeInfoRow("💳  결제", payment))

        wrapper.addView(infoCard, infoCardParams)

        val buyBtn = Button(ctx).apply {
            text = "결제하기"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(THEME_PURPLE))
                cornerRadius = dp(24f)
            }
            stateListAnimator = null
            setOnClickListener { userActionDeferred?.complete("buy") }
        }
        val buyParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(56f).toInt()
        ).apply { setMargins(0, dp(12f).toInt(), 0, 0) }
        wrapper.addView(buyBtn, buyParams)

        val cancelBtn = Button(ctx).apply {
            text = "취소"
            setTextColor(Color.parseColor(CANCEL_RED))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16f)
                setStroke(dp(1.5f).toInt(), Color.parseColor(CANCEL_RED))
            }
            stateListAnimator = null
            setOnClickListener { userActionDeferred?.complete("cancel") }
        }
        val cancelParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48f).toInt()
        ).apply { setMargins(0, dp(8f).toInt(), 0, 0) }
        wrapper.addView(cancelBtn, cancelParams)

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        overlayView = wrapper
        windowManager?.addView(overlayView, windowParams)
    }

    private fun makeInfoRow(label: String, value: String): LinearLayout {
        val ctx: Context = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8f).toInt(), 0, dp(8f).toInt())
        }

        val labelView = TextView(ctx).apply {
            text = label
            setTextColor(Color.parseColor(TEXT_GRAY))
            textSize = 13f
        }
        row.addView(labelView)

        val valueView = TextView(ctx).apply {
            text = value
            setTextColor(Color.parseColor(TEXT_DARK))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4f).toInt(), 0, 0)
        }
        row.addView(valueView)

        return row
    }

    private fun makeDivider(): View {
        val ctx: Context = this
        val divider = View(ctx).apply {
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1f).toInt()
        )
        divider.layoutParams = params
        return divider
    }

    private fun showChatOverlay(
        message: String,
        showBigBuyButton: Boolean,
        showAutoButton: Boolean = false
    ) {
        removeOverlay()

        val ctx: Context = this

        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(THEME_BACKGROUND))
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(16f).toInt())
        }

        val botBubble = TextView(ctx).apply {
            text = "🤖  $message"
            setTextColor(Color.parseColor(TEXT_DARK))
            textSize = 15f
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BUBBLE_BOT_BG))
                cornerRadius = dp(16f)
            }
            setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())
        }
        val bubbleParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        wrapper.addView(botBubble, bubbleParams)

        if (showAutoButton) {
            val autoBtn = Button(ctx).apply {
                text = "🎁  아무거나 골라줘"
                setTextColor(Color.WHITE)
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(AUTO_GREEN))
                    cornerRadius = dp(24f)
                }
                stateListAnimator = null
            }
            autoBtn.setOnClickListener {
                userActionDeferred?.complete("auto")
            }
            val autoParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50f).toInt()
            ).apply { setMargins(0, dp(12f).toInt(), 0, 0) }
            wrapper.addView(autoBtn, autoParams)
        }

        if (showBigBuyButton) {
            val buyBtn = Button(ctx).apply {
                text = "구매하기"
                setTextColor(Color.WHITE)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(THEME_PURPLE))
                    cornerRadius = dp(24f)
                }
                stateListAnimator = null
            }
            buyBtn.setOnClickListener {
                userActionDeferred?.complete("buy")
            }
            val buyParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52f).toInt()
            ).apply { setMargins(0, dp(12f).toInt(), 0, 0) }
            wrapper.addView(buyBtn, buyParams)
        }

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val rowParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(10f).toInt(), 0, 0) }
            layoutParams = rowParams
        }

        val cancelBtn = Button(ctx).apply {
            text = "취소"
            setTextColor(Color.parseColor(CANCEL_RED))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16f)
                setStroke(dp(1.5f).toInt(), Color.parseColor(CANCEL_RED))
            }
            stateListAnimator = null
        }
        cancelBtn.setOnClickListener {
            userActionDeferred?.complete("cancel")
        }

        val helpBtn = Button(ctx).apply {
            text = "도움"
            setTextColor(Color.parseColor(THEME_PURPLE))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16f)
                setStroke(dp(1.5f).toInt(), Color.parseColor(THEME_PURPLE))
            }
            stateListAnimator = null
        }
        helpBtn.setOnClickListener {
            userActionDeferred?.complete("help")
        }

        val btnParams = LinearLayout.LayoutParams(0, dp(44f).toInt(), 1f).apply {
            setMargins(dp(4f).toInt(), 0, dp(4f).toInt(), 0)
        }
        btnRow.addView(cancelBtn, btnParams)
        btnRow.addView(helpBtn, btnParams)
        wrapper.addView(btnRow)

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        serviceScope.launch(Dispatchers.Main) {
            removeOverlay()
        }
    }
}