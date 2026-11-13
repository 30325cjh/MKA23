# MKA23 · 아리스 AI 비서

Android 화면 오버레이 AI 비서 프로젝트입니다.

## 현재 상태
- 저장소 초기화 및 Android Gradle 프로젝트 구성
- 오버레이 권한 요청 / 플로팅 캐릭터 서비스
- 탭으로 캐릭터 패널 표시
- Gemini API 연동 기반 텍스트 채팅
- 검색 및 설치 앱 실행 명령의 기본 처리

## 빌드
Android Studio에서 저장소를 열고 Gradle Sync 후 app의 debug APK를 빌드하세요. Android SDK 35와 JDK 17이 필요합니다.

## 리소스
원본 캐릭터 이미지와 MP3는 별도 바이너리 자산입니다. 이 저장소의 초기 버전에는 아직 업로드되지 않았으며, MKA2.zip과 효과음.zip에서 아래 리소스 경로로 복사해야 완전한 화면을 구성할 수 있습니다.

- app/src/main/res/drawable/arisu_default.jpg, arisu_smile.jpg, arisu_question.jpg, arisu_big_smile.jpg, arisu_confident.jpg, arisu_halo.webp
- app/src/main/res/raw/confirm.mp3, laugh.mp3, question.mp3, question2.mp3, question3.mp3, surprise.mp3, confused.mp3, static.mp3, success.mp3

APK 빌드와 Android 실기기 검증은 아직 수행하지 않았습니다.
