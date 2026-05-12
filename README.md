# 사용방법
```
git clone https://github.com/qkrCksDlf/ondevice-accessibility-agent.git

cd ondevice-accessibility-agent
```
---
이후 app/src/main/cpp폴더 안에 llama.cpp를 다운받아야함. 그냥 최신버전은 안되고 b4500 버전 이용.
```
cd app/src/main/cpp
git clone https://github.com/ggerganov/llama.cpp
cd llama.cpp

# 3. b4500 태그로 체크아웃
git checkout b4500

# 4. 서브모듈 초기화 (필요시)
git submodule update --init --recursive
```
---
이후 허깅페이스에서 모델 다운 후 app/src/main 안에 assets폴더를 만듭니다. (폴더 이름을 assets로)
그리고 그 assets폴더 안에 models폴더를 만들고 그 안에 넣으시면 됩니다.
![Architecture](images/models.jpg)

llama-3.2-3b-instruct-q4_k_m.gguf를 다운받으시면 됩니다. 
혹은 다른 모델 사용하고 싶으시면 그 모델을 다운 받아서 옮긴 후 메인엑티비티.kt에서 MODEL_ASSET_PATH찾으신 후 해당 모델 이름에 맞게 바꿔주세요.
