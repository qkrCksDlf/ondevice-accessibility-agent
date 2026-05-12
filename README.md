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

이후 허깅페이스에서 모델 다운받은 후 
