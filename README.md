# On-Device Accessibility Agent

> 디지털 취약계층을 위한 **온디바이스 LLM 기반 스마트폰 도우미**

캡스톤 디자인 프로젝트 결과물입니다.

---

## 🎯 프로젝트 개요

화면 분석 및 자동 조작이 필요한 스마트폰 도우미 앱은 사용자의 화면 정보, 앱 상태, 입력 내용 등 민감한 데이터에 접근할 수 있습니다.

본 프로젝트는 이러한 민감한 정보가 외부 서버로 전송되지 않도록, **LLM 추론을 디바이스 내부에서 수행하는 온디바이스 에이전트**를 구현하는 것을 목표로 합니다.

### 핵심 목표

- 사용자 입력을 자연어로 해석
- LLM을 통해 명령을 구조화된 JSON 형태로 변환
- Android Accessibility Service를 이용해 실제 스마트폰 UI 자동 조작
- 민감한 화면 데이터와 사용자 명령을 외부 서버로 전송하지 않는 로컬 추론 구조 구현

---

## 🧠 주요 기능

- 텍스트 기반 사용자 명령 입력
- 온디바이스 LLM을 통한 명령 해석
- JSON 기반 명령 구조화
- Android Accessibility API 기반 UI 자동 조작
- 카카오톡, 배달앱, 설정 등 다양한 앱 제어 확장 가능
- 의도하지 않은 실행을 방지하기 위한 사용자 확인 단계 설계

---

## 🛠️ 기술 스택

| Category | Stack |
|---------|-------|
| Platform | Android |
| Language | Kotlin, C++ |
| Native Bridge | JNI |
| LLM Engine | llama.cpp |
| Model | Llama 3.2 3B Instruct GGUF |
| Quantization | Q4_K_M |
| Build | Android NDK, CMake |
| Acceleration | CPU, Vulkan GPU Experimental |
| Device | Samsung Galaxy, Exynos 2400 |

---

## ⚙️ 시스템 구조

```text
User Input
    ↓
Prompt Builder
    ↓
On-Device LLM
    ↓
JSON Command
    ↓
Agent Controller
    ↓
Android Accessibility Service
    ↓
Smartphone UI Automation

LLM은 사용자의 자연어 명령을 직접 실행하지 않고, 아래와 같은 구조화된 JSON 명령으로 변환합니다.

{
  "intent": "send_message",
  "app": "kakaotalk",
  "target": "홍길동",
  "message": "지금 출발할게"
}

이후 Android Accessibility Service가 해당 JSON 명령을 기반으로 실제 앱 UI를 탐색하고 조작합니다.

⚡ 성능 최적화

온디바이스 LLM은 서버 기반 LLM보다 연산 자원이 제한적이기 때문에, 단순히 모델을 실행하는 것만으로는 실사용에 적합한 응답 속도를 얻기 어렵습니다.

본 프로젝트에서는 다음과 같은 최적화를 적용했습니다.

Optimization	Purpose
Flash Attention	Attention 연산 효율 개선
KV Cache Q8 Quantization	KV cache 메모리 사용량 감소
Prefix KV Cache Reuse	반복되는 프롬프트 재계산 방지
Minimal JSON Output	생성 토큰 수 감소
Short System Prompt	Prompt processing 비용 감소
🧩 KV Cache Optimization

KV cache는 단순히 시스템 프롬프트만 저장하는 것이 아니라, 이미 계산된 prefix token들의 Key/Value attention state를 저장합니다.

즉, 이전 요청에서 동일하게 반복되는 prefix가 있다면 다음 요청에서는 해당 부분을 다시 계산하지 않고 재사용할 수 있습니다.

캐시 재사용 대상
System prompt
고정 instruction
이전 대화 context
반복되는 prefix tokens
새로 계산되는 대상
새 사용자 입력
새로 생성되는 output tokens

기존에는 매 요청마다 전체 prompt를 다시 처리해야 했습니다.

System Prompt + Context + User Input

KV cache 적용 후에는 이미 계산된 prefix를 재사용하고, 새로 들어온 사용자 입력 부분만 추가로 처리합니다.

Cached Prefix + New User Input

이를 통해 prompt processing 비용을 크게 줄일 수 있었습니다.

📊 성능 측정 결과
테스트 환경
Item	Value
Device	Samsung Galaxy
SoC	Exynos 2400
GPU	Xclipse 940
Model	Llama 3.2 3B Instruct Q4_K_M
Engine	llama.cpp b4500
Backend	CPU / Vulkan GPU
1. CPU Only vs Vulkan GPU
Vulkan GPU Mode
Prompt: 214 tokens in 5410 ms = 39.56 tok/s
Gen:     81 tokens in 4933 ms = 16.42 tok/s

Prompt: 228 tokens in 5430 ms = 41.99 tok/s
Gen:     55 tokens in 3294 ms = 16.70 tok/s
CPU Only Mode
Prompt: 214 tokens in 5535 ms = 38.66 tok/s
Gen:     81 tokens in 5838 ms = 13.87 tok/s

Prompt: 228 tokens in 5147 ms = 44.30 tok/s
Gen:     55 tokens in 3270 ms = 16.82 tok/s
Result
Mode	Prompt Speed	Gen Speed
CPU Only	약 41.48 tok/s	약 15.35 tok/s
Vulkan GPU	약 40.78 tok/s	약 16.56 tok/s

Vulkan GPU offloading은 CPU only 대비 generation 속도를 약 7.9% 개선했습니다.

(16.56 - 15.35) / 15.35 × 100 ≈ 7.9%

하지만 prompt processing 속도는 거의 개선되지 않았으며, 모바일 UMA 구조에서는 GPU offloading의 효과가 제한적임을 확인했습니다.

2. KV Cache 적용 결과
CPU + KV Cache
Prompt: 27 tokens in 1594 ms = 16.94 tok/s (cache: HIT)
Gen:    83 tokens in 5766 ms = 14.39 tok/s

Prompt: 41 tokens in 3229 ms = 12.70 tok/s (cache: HIT)
Gen:    67 tokens in 4140 ms = 16.18 tok/s
Vulkan GPU + KV Cache
Prompt: 27 tokens in 867 ms = 31.14 tok/s (cache: HIT)
Gen:    83 tokens in 4950 ms = 16.77 tok/s

Prompt: 41 tokens in 3164 ms = 12.96 tok/s (cache: HIT)
Gen:    67 tokens in 4325 ms = 15.49 tok/s
3. Optimization Impact
Configuration	Prompt Tokens	Prompt Time	Gen Speed
CPU Only	214~228 tokens	5.1~5.5s	13.9~16.8 tok/s
Vulkan GPU	214~228 tokens	약 5.4s	16.4~16.7 tok/s
CPU + KV Cache	27~41 tokens	1.6~3.2s	14.4~16.2 tok/s
Vulkan + KV Cache	27~41 tokens	0.9~3.2s	15.5~16.8 tok/s
🔍 Key Findings
1. Vulkan GPU offloading의 효과는 제한적이었습니다.

Vulkan GPU는 CPU only 대비 generation 속도를 약 7.9% 개선했습니다.

하지만 prompt processing 속도는 거의 개선되지 않았고, 전체 응답 시간 관점에서는 큰 차이를 만들지 못했습니다.

2. KV cache는 generation 속도보다 prompt processing 비용을 줄이는 데 효과적이었습니다.

KV cache 적용 후 처리해야 하는 prompt token 수가 다음과 같이 감소했습니다.

214 tokens → 27 tokens
228 tokens → 41 tokens

이는 약 82~87%의 prompt token 재계산 감소에 해당합니다.

214 → 27: 약 87.4% 감소
228 → 41: 약 82.0% 감소
3. 모바일 환경에서는 GPU offloading보다 메모리/캐시 최적화가 더 큰 실사용 효과를 보였습니다.

본 실험에서 GPU offloading은 generation tok/s를 소폭 개선했지만, KV cache는 반복되는 prefix prompt 재계산을 줄여 실제 응답 시간을 더 크게 개선했습니다.

따라서 모바일 온디바이스 LLM 환경에서는 단순 GPU offloading보다 다음 최적화가 더 중요할 수 있음을 확인했습니다.

KV cache 재사용
KV cache 메모리 최적화
Prompt 길이 감소
Output token 수 감소
JSON 기반 짧은 응답 생성
🚀 빌드 방법
1. 사전 요구사항
Android Studio Hedgehog 이상
Android NDK 27.0.12077973
CMake 3.22.1
Vulkan SDK 1.4.341.1, Vulkan 빌드 시
Visual Studio Community, Vulkan shader build 시
2. 저장소 클론
git clone https://github.com/qkrCksDlf/ondevice-accessibility-agent.git
cd ondevice-accessibility-agent
3. llama.cpp 클론

llama.cpp는 용량 및 라이선스 관리를 위해 별도로 클론합니다.

cd app/src/main/cpp
git clone https://github.com/ggerganov/llama.cpp
cd llama.cpp
git checkout b4500
git submodule update --init --recursive
4. Vulkan 빌드 시 패치 적용

본 프로젝트에서는 llama.cpp b4500의 CoopMat shader generation 관련 빌드 문제를 우회하기 위해 일부 CMake 설정을 수정했습니다.

Copy-Item "patches/llama.cpp-b4500/ggml-vulkan-CMakeLists.txt" `
          "app/src/main/cpp/llama.cpp/ggml/src/ggml-vulkan/CMakeLists.txt"

또는 직접 다음 라인을 주석 처리합니다.

# add_compile_definitions(GGML_VULKAN_COOPMAT_GLSLC_SUPPORT)
# add_compile_definitions(GGML_VULKAN_COOPMAT2_GLSLC_SUPPORT)
5. 모델 다운로드

사용 모델:

Llama 3.2 3B Instruct Q4_K_M GGUF

예시 모델 저장소:

bartowski/Llama-3.2-3B-Instruct-GGUF

다운로드한 .gguf 파일을 Android 앱 내부 저장소의 다음 경로에 배치합니다.

/data/user/0/com.example.helpagent/files/models/
6. 빌드 및 실행

Android Studio에서 다음을 실행합니다.

Build > Make Project
Run
🔧 설정 옵션
CPU Only Mode, 권장
model_params.n_gpu_layers = 0;
"-DGGML_VULKAN=OFF"

CPU only mode는 모바일 환경에서 가장 안정적으로 동작하며, 본 프로젝트의 기본 권장 설정입니다.

Vulkan GPU Mode, Experimental
model_params.n_gpu_layers = 99;
"-DGGML_VULKAN=ON"

Vulkan GPU mode는 실험적으로 지원되지만, 측정 결과 모바일 환경에서는 CPU 대비 성능 개선폭이 제한적이었습니다.

📁 프로젝트 구조
ondevice-accessibility-agent/
├── app/
│   ├── src/main/
│   │   ├── cpp/
│   │   │   ├── native-lib.cpp
│   │   │   ├── CMakeLists.txt
│   │   │   └── host-toolchain/
│   │   ├── java/com/example/helpagent/
│   │   │   ├── MainActivity.kt
│   │   │   ├── AgentController.kt
│   │   │   ├── AutoAgentService.kt
│   │   │   └── AgentCommand.kt
│   │   └── res/xml/
│   │       └── accessibility_service_config.xml
│   ├── build.gradle.kts
├── patches/
│   └── llama.cpp-b4500/
└── README.md
🧪 Demo Flow
1. 사용자 입력
   "카카오톡으로 엄마한테 지금 출발한다고 보내줘"

2. 온디바이스 LLM 명령 해석

3. JSON 명령 생성

4. Accessibility Service 실행

5. 카카오톡 UI 자동 탐색 및 메시지 전송 준비

6. 사용자 확인 후 최종 실행
🧠 핵심 인사이트

본 프로젝트를 통해 모바일 온디바이스 LLM 환경에서는 단순히 GPU를 사용하는 것보다, 반복되는 prompt 처리 비용을 줄이고 생성 토큰 수를 최소화하는 것이 더 중요하다는 것을 확인했습니다.

특히 Accessibility Agent처럼 동일한 system prompt와 instruction이 반복되는 구조에서는 KV cache 재사용이 실사용 응답 시간 개선에 큰 영향을 줄 수 있습니다.

GPU offloading:
- Generation speed 약 7.9% 개선

KV cache reuse:
- Prompt token 재계산 약 82~87% 감소

결론적으로, 모바일 온디바이스 LLM 최적화에서는 다음 전략이 효과적이었습니다.

Short Prompt
+ Prefix KV Cache Reuse
+ Minimal JSON Output
+ CPU-first Stable Execution
🤝 기여

학부 캡스톤 디자인 프로젝트입니다.
Issue와 Pull Request를 환영합니다.

📜 라이선스

MIT License

🙏 감사
llama.cpp
Meta Llama 3.2
GGUF community models
