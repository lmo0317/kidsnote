# 키즈노트 사진·영상·알림장 저장 도우미

> 키즈노트 사진·영상 저장을 돕는 독립적인 비공식 앱이며, 키즈노트 운영사와 무관합니다.

별도 웹 서버 없이 안드로이드 앱에서 키즈노트에 직접 로그인하고 사진을 보거나 사진·영상·알림장을 저장하는 앱입니다.

## 기능

- 앱 내부 WebView 키즈노트 로그인
- 키즈노트 API 직접 연결
- 연도별 3열 사진 갤러리
- 사진 원본, 영상 원본과 알림장 텍스트를 기기에 직접 저장
- 저장 폴더를 `Pictures/KidsNote/<년>/<월>/<일>` 또는 `<년>/<월>` 구조로 선택
- 일별 폴더를 선택하면 사진·알림장을 `Pictures/KidsNote`, 영상을 `Movies/KidsNote` 날짜 폴더에 저장
- 서버 사진 저장, 서버 세션, 서버 ZIP 생성 없음

## 빌드

```powershell
.\gradlew.bat assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`
