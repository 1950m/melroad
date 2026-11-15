# MELROAD 테스트 보고서

작성: 2026-11-15 · 실제로 실행한 항목만 "통과"로 적는다.

## 1. 개발 환경(실측)

| 항목 | 값 |
|---|---|
| OS | macOS 15.5 (arm64) |
| JDK | OpenJDK 17.0.19 (Homebrew) |
| Gradle / AGP / Kotlin | 8.9 / 8.7.3 / 2.0.21 |
| compileSdk / targetSdk / minSdk | 35 / 34 / 28 (Media3 1.5.1이 compileSdk 35 요구) |
| Android SDK 플랫폼 | android-34, 35, 36, 37.0 |
| Emulator | 36.3.10.0 |
| 설치된 AVD | Pixel_9 (API 36.1), Pixel_Tablet (API 35, 2560×1600, 320dpi) |
| **Pleos Connect 시스템 이미지·AVD** | **설치되어 있지 않음** → Pleos 검증 미실시 |

## 2. 실행한 명령과 결과

| 명령 | 결과 |
|---|---|
| `./gradlew :core-music:test` | **통과** 25/25 (DeterminismTest 4, FixtureMusicTest 14, SignalNormalizerTest 7) |
| `./gradlew test` (app 포함) | **통과** — OfflineRenderTest 1/1(오케스트라 샘플 사용), PleosApiProbeTest 1/1 (2026-11-15 18:17) |
| `./gradlew :app:assembleDebug` | **성공** (경고: API 35 SDK에서 `CarPropertyManager.registerCallback` 사용 중단 표시. 실행 대상 API 34에서는 정상 API) |

### 요구서 TC 대응

| TC | 내용 | 상태 |
|---|---|---|
| TC-01~03 | coast/hill/city가 서로 다른 음악, 굽이·오르막·정차 반영 | 통과(유닛) |
| TC-04 | 같은 fixture+seed 3회 해시 일치, pump 간격과 무관 | 통과(유닛) |
| TC-06 | 위치 60초 null → SPEED_ONLY, 가짜 커브·오르막 없음 | 통과(유닛) |
| 장기 여정 | 30분 해안도로: 구간 4종 이상, 조바꿈 3회 이상, 4마디 선율 60% 이상 서로 다름 | 통과(유닛) |
| 음량 | 오프라인 렌더 peak 0.71~0.85, RMS 약 0.12, 클리핑 없음 | 통과(유닛) |
| TC-05 | 권한 거부 시 LIVE 자동 시작 금지 | 코드 구현, **기기 미검증** |
| TC-07 | 주행 중 제한 | 코드 구현, **미검증**(Pleos AVD 없음) |
| TC-08 | 통화·안내 포커스 | 코드 구현, **미검증** |
| TC-10 | TalkBack만으로 MEMORY 재생 | 콘텐츠 설명 확인(ui dump), **TalkBack 실사용 미검증** |
| TC-11 | release APK Pleos 설치 | **미실시** |

## 3. 에뮬레이터 확인(Pixel_Tablet, API 35 — Pleos 아님, 사전 점검용)

| 흐름 | 결과 |
|---|---|
| 설치·실행 | 성공. 실행만으로 재생되지 않고 첫 화면 표시(자동 재생 없음) |
| 시뮬레이션 시작 | 제주 해안도로 선택 → "시뮬레이션" 배지·진행 시간(30:56) 표시, 재생 (2026-11-15 재확인) |
| Media3 세션 | `dumpsys media_session`에서 PLAYING, 미디어 버튼 세션 = 멜로드 |
| 여행 마치기 | "마무리 중" → 종지 연주 → "완료"(길이 0:56, 이전 문구 기준 확인 후 문구만 간결하게 변경) |
| 저장 | "저장됨" |
| 우리의 음악 | 날짜·길이·시뮬레이션 구분 표시, 재생/삭제 버튼에 TalkBack 설명 |
| 다시 듣기 | 저장된 이벤트 그대로 재생(0:09 / 0:56), 세션 active |
| 크래시 로그 | 멜로드 관련 0건 |

| 새 첫 화면(아이콘 버튼·새 심볼) | **미확인** — `INSTALL_FAILED_INSUFFICIENT_STORAGE`(에뮬레이터 내부 저장공간 부족). 저장공간 확보 후 재설치 필요 |

## 4. API 상태

| API | 상태 |
|---|---|
| CarPropertyManager PERF_VEHICLE_SPEED | 구현, **미검증**(휴대폰·태블릿 이미지는 차량이 아니라 NOT_AUTOMOTIVE로 안내) |
| CarUxRestrictionsManager | 구현, **미검증** |
| Pleos FusedLocation 1.1.3 | **어댑터 구현, 빌드 통과, 기기 미검증**. 공식 문서 순서(isSupported → 권한 → initialize → registerFusedLocationCallback / unregister → release)와 SDK에서 확인한 API만 사용. `LocationData(latitude, longitude, altitude, heading, confidence, timestampMillis)` — 문서에 단위·척도 설명이 없어 confidence는 미사용, heading은 0~360 유한값만, 고도는 미터로 가정(미검증). 오류 코드 클래스는 실행 시 모듈(fused-location-data 0.0.3)에 있어 이름으로 판별. 권한 `pleos.car.permission.FUSED_LOCATION`(문서상 dangerous) 선언·실행 중 요청 |
| Pleos Vehicle 2.3.2 | 의존성 해석·클래스 확인 완료. `Vehicle(Context).getGear()` → `registerGearSelection(GearSelectionListener)`, `VehicleGear`(PARK/DRIVE 등). 권한 문자열(`pleos.car.permission.CAR_DRIVE` 등) 확인, API별 필요 권한은 **미확인**. 어댑터 **미구현** |
| SDK가 병합하는 manifest 항목 | `<queries>` FusedLocation 서비스 패키지, `FUSED_LOCATION_BINDING`, `VEHICLE_BINDING`, `ACCESS_NETWORK_STATE`(SDK 제공, 앱은 네트워크 통신 없음) |
| NaviHelper | 미구현(MVP 범위 아님) |
| Media3 MediaLibraryService(MBS) | 태블릿에서 세션 동작 확인, 차량 미디어 센터 **미검증** |

## 5. 다음 검증

1. Pleos Connect v2.1.0 AVD 설치 후 동일 흐름 재검증, 화면 규격 기록
2. VHAL 속도 주입(`cmd car_service inject-vhal-event 0x11600207 <m/s>`)으로 LIVE 속도 반영 확인
3. 주행 상태 전환 시 UX 제한, 통화·안내 포커스, TalkBack 완주
4. release 서명 APK 설치 검증
