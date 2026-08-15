# 키즈노트 사진 저장 도우미

> 키즈노트 사진 저장을 돕는 독립적인 비공식 앱이며, 키즈노트 운영사와 무관합니다.

별도 웹 서버 없이 안드로이드 앱에서 키즈노트에 직접 로그인하고 사진을 보는 앱입니다.

## 기능

- 앱 내부 WebView 키즈노트 로그인
- 키즈노트 API 직접 연결
- 연도별 3열 사진 갤러리
- 사진을 `Pictures/KidsNote/<연도>`에 직접 저장
- 서버 사진 저장, 서버 세션, 서버 ZIP 생성 없음

## 빌드

```powershell
.\gradlew.bat assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`
