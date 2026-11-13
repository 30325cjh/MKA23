# MKA23 · 아리스 비서 (0.2.0)

## 이번 버전: 오프라인 로그인 연출
- AI/Gemini 연결과 API 키 입력 UI 제거
- 오버레이 권한 허용 후 비서 서비스 시작
- 플로팅 아리스 아이콘을 누르면 기본 이미지와 확인 사운드로 등장
- "연결 완료..." 표시
- 약 0.95초 후 웃는 이미지로 전환
- 캐릭터가 짧고 빠르게 위아래로 바운스
- 성공 사운드와 "선생님! 아이리스, 로그인 성공입니다!" 출력
- 헤일로의 느린 부유 애니메이션

## 필요한 앱 리소스
- drawable: arisu_default.webp, arisu_smile.webp, arisu_halo.webp
- raw: confirm.mp3, success.mp3

## 빌드
Android Studio에서 프로젝트를 열고 Gradle Sync 후 app의 debug APK를 빌드합니다. Android SDK 35 및 JDK 17 필요.

참고: 소스 변경은 GitHub에 반영했지만, APK는 아직 빌드/실기기 검증 전입니다.