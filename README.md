# MELROAD (멜로드)

> 같은 풍경을 볼 수 없어도, 함께 추억할 수 있도록.

차의 움직임으로 만들어진 음악을 함께 듣고, 함께한 길을 다시 들을 수 있게 하는 차량용(Android Automotive OS / Pleos Connect) 음악 앱입니다.
시각장애인·저시력 어르신 동승자와 가족이 같은 길을 음악으로 함께 기억하는 것을 목표로 합니다.
길 안내·위험 감지·풍경 설명 기능이 아니며, 시각을 대신한다고 주장하지 않습니다.

## 모드

| 모드 | 입력 | 표시 |
|---|---|---|
| LIVE | 실제 차량 신호(현재: AAOS 차량 속도만) | "실제 주행 · 속도 기반" |
| DEMO | 사전 제작 합성 주행 데이터(제주 해안도로 약 30분 / 언덕길 약 20분 / 도심 약 15분) | 시작 화면의 "시뮬레이션" 구역·저장된 곡 제목·화면 낭독기 안내에 "시뮬레이션" 표기(주행 화면에는 글자 표시 없음) |
| MEMORY | 저장된 MusicEvent 타임라인을 그대로 재생(새로 작곡하지 않음) | 실제 주행/시뮬레이션 구분 표시 |

실제 신호가 없거나 권한이 거절되면 LIVE를 시작하지 않고, DEMO로 자동 전환하지 않습니다.

## 구조

```
app/                 Android 앱 (Kotlin, Jetpack Compose)
  ui/                S01~S07 화면, 고대비·대형 버튼·TalkBack 의미 구조
  session/           LIVE/DEMO/MEMORY 상태 머신 (TripSessionController)
  audio/             자체 합성 렌더러(AudioTrack), 오디오 포커스, Media3 MediaLibraryService(MBS 호환)
  data/              Room (trips, music_events, playback_state)
  integration/       VehicleSignalSource: RealVehicleSignalSource / DemoVehicleSignalSource
    car/             CarPropertyManager(PERF_VEHICLE_SPEED), CarUxRestrictionsManager 어댑터
core-music/          순수 Kotlin 결정론적 작곡 엔진 (Android 의존성 없음)
sampledata/          시뮬레이션 데이터 설명
```

음악 매핑: 속도 → 반주 밀도(음량·템포는 올리지 않음), 진행 방향 변화 → 다음 악절 화성·선율 방향, 검증된 고도 변화 → 음역·패드 색채.
큰 변화는 4마디 경계에서만, 약 12초 누적 특징만 반영합니다. 같은 seed + 테마 + 입력이면 같은 이벤트가 나옵니다(SHA-256 해시 테스트).
긴 여정에서는 도입 → 주제 → 후렴 → 간주 → 브리지가 순환하고 약 7분마다 조가 바뀌며, 정차하면 쉼 구간으로 숨을 고릅니다.
음색은 외부 샘플 없이 코드로 직접 합성합니다(피아노·일렉트릭 피아노·현악·기타·패드·베이스·소프트 타악).

## 빌드·실행

- Android Studio에서 이 폴더를 열고 Gradle Sync → `app` 실행
- 단위 테스트: `./gradlew :core-music:test`
- 버전: AGP 8.7.3 / Gradle 8.9 / Kotlin 2.0.21 / compileSdk 35 · targetSdk 34 / minSdk 28
- 대상 에뮬레이터: Pleos Connect v2.1.0 (Automotive, API 34)

## 권한

| 권한 | 용도 | 상태 |
|---|---|---|
| `android.car.permission.CAR_SPEED` | 차량 속도(LIVE) | 선언, 런타임 요청 |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Media3 재생 서비스 | 선언 |
| `pleos.car.permission.FUSED_LOCATION` | 위치·방향·고도 | **미선언** (어댑터 미구현) |

## 개인정보

음악 이벤트와 최소 여정 정보(날짜·길이·실제/시뮬레이션 구분)만 기기에 저장합니다. 원본 좌표·목적지는 저장하지 않으며 서버·광고·추적 SDK가 없습니다.

## 현재 구현·검증 상태

자세한 실행 결과는 [`docs/test-report.md`](docs/test-report.md). 실제로 실행해 확인한 것만 "확인"으로 적는다.

| 항목 | 상태 |
|---|---|
| core-music 작곡 엔진(장시간 구간 순환·조바꿈·스윙·분산화음·현악)·정제·fixture | 유닛 테스트 25개 통과 |
| 오케스트라 샘플 음색(피아노·현악 합주·하프·첼로 피치카토·팀파니 롤) + 합성 음색 대체 경로, 홀 잔향·마스터 버스 | 오프라인 렌더 테스트 통과(클리핑 없음), 기기에서 소리 미검증 |
| 화면(S01~S07), 주행 장면, 입체 선율 그래픽, 방위계 | 태블릿 에뮬레이터(API 35)에서 확인. 아이콘 중심 새 첫 화면은 에뮬레이터 저장공간 부족으로 설치 미확인 |
| 시뮬레이션 시작 → 마치기 → 저장 → 다시 듣기 | 태블릿 에뮬레이터에서 확인 |
| Media3 세션 | 태블릿에서 PLAYING 확인, 차량 미디어 센터 미검증 |
| 차량 속도 어댑터(P5) | 구현, Pleos AVD 미검증 |
| CarUxRestrictions(P7) | 구현, 미검증 |
| FusedLocation(P6) | SDK 1.1.3 클래스·필드 확인 완료, 어댑터 **미구현** |
| Pleos Connect AVD 설치·실행 | **미실시**(시스템 이미지 미설치) |
| 시각장애인·저시력 당사자 사용성 검증 | 미진행 |

## 음원 출처

앱에 들어간 악기 샘플은 아래 자료만 사용한다(변환: `tools/build_orchestra.py`, 상세: `app/src/main/assets/orchestra/LICENSE.txt`).

- [VSCO 2 Community Edition](https://github.com/sgossner/VSCO-2-CE) — CC0 1.0. 바이올린·비올라·첼로 합주, 첼로 피치카토, 하프, 팀파니
- Upright Piano — Simon Dalzell / Ivy Audio (VSCO 2 CE 포함, Versilian Studios가 재배포 허용)

작곡은 규칙 기반·결정론적이며 생성형 AI를 쓰지 않는다.

## 한계

- 공모전 시연 단계의 앱입니다. 실제 차량 위치 연동과 당사자 사용자 테스트는 아직 진행 전입니다.
- 앱 강제 종료 시 저장 전 여정은 복구되지 않습니다.
