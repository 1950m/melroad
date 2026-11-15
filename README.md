# 멜로드 (MELROAD)

멜로드(MELROAD)는 자동차의 움직임을 음악으로 바꾸어, 시각장애인과 시력이 약해진 어르신도 가족과 함께 달린 길을 느끼고 그날의 여행을 추억할 수 있도록 돕는 차량용 앱입니다.

## 실행 방법

1. Android Studio에서 프로젝트를 열고 `app`을 실행합니다.
2. 첫 화면 가운데 버튼을 누르고 테스트 1~3 중 하나를 고릅니다.
3. 종료를 누른 뒤 뮤직박스에 저장하면 같은 곡을 다시 들을 수 있습니다.

## 테스트 환경 및 필요한 설정

- 에뮬레이터: Android Studio Pixel Tablet, API 35, 2560×1600
- 개발 도구: AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21 (compileSdk 35 / targetSdk 34 / minSdk 28)
- 필요한 설정: 실제 차량에서는 차량 속도와 Pleos 위치 권한을 허용해야 합니다.

## Pleos Connect 에뮬레이터

- Pleos Connect v2.1.0 (Android Automotive OS, API 34)

## APK

- GitHub Releases: https://github.com/1950m/melroad/releases
