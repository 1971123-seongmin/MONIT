# 백업·복원 성능 및 장애 계측 지원 설계

Issue [#72](https://github.com/1971123-seongmin/MONIT/issues/72). 최초 설계 기준 main `dd16d95122400ea2ef3a1be593a41f8e5592b854`. **PM 기술 선택 채택(2026-11-14)**. 전용 performanceQa·변형 전용 bridge·per-instance no-op probe를 최소 구현한다. 이 문서는 설계이며 실기기 성능 측정 결과가 아니다. PR #71 작업 공간/APK와 기존 앱 자료는 동결 보존한다.

## 기준과 현재 차이

공통 작업 공간의 로컬 원본 `docs/architecture/local-mvp-android-plan.md` §8.4·§11(T1~T6), `docs/testing/local-mvp-test-plan.md` C7(B1~B11)을 2026-11-14 읽었다. 해당 미추적/미커밋 문서는 이 브랜치에 복사하지 않는다. 다음 표는 이번 지원에 필요한 기준만 대조한다.

실행 계약은 [BACKUP.md](../../core/data/BACKUP.md)와 실제 코드다. 구 계획의 논리 v1 root를 새 fixture 형식으로 재도입하지 않는다. 현재 논리 v3/schema9를 사용하고 envelope1/cryptoSuite1, KDF600000·52byte AAD·자원 한도는 유지한다.

| 기준 | 현재 구현과 근거 | 이번 지원 / 남는 경계 |
|---|---|---|
| T1·T5 / B11 | [RoomBackupRepository](../../core/data/src/main/kotlin/com/monit/core/data/backup/RoomBackupRepository.kt)의 유지보수 경계·검증 사본·단일 transaction·영속 결과 | 실제 파일 Room의 commit 전 실패/취소·commit 후 응답 유실. 전원 차단·프로세스 강제 종료는 예외 주입 결과와 구분 |
| T2 / B1·B2·B7 | [BackupEnvelope](../../core/data/src/main/kotlin/com/monit/core/data/backup/BackupEnvelope.kt)의 플랫폼 JCA·고정 header·Unicode 검증과 기존 golden vector | 계측 환경에서 같은 vector·변조 거부. SDK36 S23 결과로 API26/다른 provider를 통과 처리하지 않음 |
| T3 / B3~B6 | [BackupJson](../../core/data/src/main/kotlin/com/monit/core/data/backup/BackupJson.kt), [BackupValidator](../../core/data/src/main/kotlin/com/monit/core/data/backup/BackupValidator.kt)의 strict parser·manifest·관계/한도 검증 | 유효 상한과 바로 초과 입력을 분리. 유효하지 않은 거부 경로의 짧은 시간을 정상 상한 성능으로 사용하지 않음 |
| T4 / B10 | `CRYPTO`에 KDF/GCM이 함께 있고 `VALIDATION`에 parse/논리 검증이 함께 있음 | 내부 시작/종료 관찰 지점으로 각각 raw 시간 수집. 기존 debug UI 지연 안내는 측정값으로 사용하지 않음 |
| T5 / B5·B10 | [BackupRestorePlan](../../core/data/src/main/kotlin/com/monit/core/data/backup/BackupRestorePlan.kt)은 관계 순서로 전체 대체. `REPLACEMENT`는 정확한 SQL commit 시간 아님 | transaction 진입 전·본문·commit 반환 경계 수집, 실제 교체 20표본. 준비/복구 사본 시간도 별도 보관 |
| T2·T5 / B8 | [KeystoreRecoveryCopyStore](../../app/src/main/java/com/monit/app/backup/KeystoreRecoveryCopyStore.kt)의 private key 경로는 주입 불가 | key 접근 경계에 실패를 주입해 평문 fallback/교체0 확인. 실제 alias 삭제·무효화·교체로 실패를 만들지 않음 |
| T3·T4·T6 / B4·B9 | [BackupDocuments](../../app/src/main/java/com/monit/app/backup/BackupDocuments.kt)의 실제 ContentResolver·PFD·재읽기·협력 취소 | 전용 DocumentsProvider의 open/읽기/쓰기/재읽기 실패·stall/취소. 실제 OEM close·권한 회수는 별도 QA 증거 |
| T6 | 기존 알림/OS 재조정 경로 | 알림 실패가 복원 재실행을 만들지 않는 경계만 포함. 재부팅·시간 변경·강제 Doze는 기기 영향 계획 없이 실행하지 않음 |

## 권장 구성과 보호 경계

**새 모듈·Benchmark 프레임워크 없이 전용 변형과 작은 AndroidJUnitRunner 파생 runner를 사용한다.** 기존 Robolectric/JUnit 회귀에 전용 androidTest source·runner를 추가했다. 실제 앱 UI의 시간을 재거나 기존 QA 앱에 계측 APK를 붙이지 않는다.

| 산출물 | 식별자 / 역할 |
|---|---|
| 대상 APK `performanceQa` | `com.monit.app.qa.performance`, 표시 이름 `MONIT 합성 성능 검증`. app/core:data에 release에서 초기화한 같은 이름의 변형을 두고 다른 모듈은 release로 fallback. debuggable=false이며 현재 release와 같은 최적화 설정 |
| instrumentation APK | `com.monit.app.qa.performance.test`, targetPackage를 위 ID로 고정 |
| 서명 | 기존 개발용 debug signingConfig 재사용. 빌드 후 실제 cert/SHA를 대조하며 다른 서명·keystore 파일을 생성하지 않음 |
| 합성 저장소 | 새 UID 내부의 `backup-perf-72-<runId>-<sample>.db`와 `filesDir/backup-probe-72/<runId>`만 사용. 기존 앱 DB를 열지 않음. 사본 store의 공간 검사에는 전용 ContextWrapper가 제품의 고정 DB 이름을 해당 시험 DB/WAL/SHM으로 대응 |
| 원래 앱 | `com.monit.app`, `.qa`, `.qa.backup`에는 설치/계측/clear-data/DB·키 접근을 하지 않음 |

동일 서명이라도 다른 applicationId의 앱 자료/Keystore가 공유된다고 가정하지 않는다. 시작 시 target/test package·실제 UID·DB 파일의 정규화 경로를 확인한다. runner는 plain Application으로 시작해 MonitApplication의 기본 DB/알림 observer를 가동하지 않는다. 실제 플랫폼 Context에서 DB/Repository/SAF 클래스를 생성하며 Hilt/UI 시작 시간을 재는 시험은 아니다. fixture 생성·class loading은 측정 구간 밖이고 제품 Activity를 실행하지 않는다.

최소 의존성은 test scope의 `androidx.test:runner:1.7.0`, `androidx.test.ext:junit:1.3.0`와 기존 Kotlin/JUnit이다. Hilt test graph·새 DI 프레임워크·UIAutomator·Macrobenchmark·Orchestrator는 이번 직접 호출 계측에 추가하지 않는다. 버전은 [AndroidX Test 공식 stable 표](https://developer.android.com/jetpack/androidx/releases/test)에서 2026-11-14 확인했다. [testBuildType/별도 test APK](https://developer.android.com/studio/test/advanced-test-setup)를 사용하며 실제 task/manifest와 non-debuggable 대상의 동일 서명 계측 연결은 구성 후 확인한다. 실제 배포 APK의 성능을 측정했다는 뜻은 아니며 최적화/debuggable/프로파일 설정을 raw에 기록한다.

## 구체적인 최소 변경안

| 파일/위치 | 필요한 변경 |
|---|---|
| `app/build.gradle.kts`, `core/data/build.gradle.kts`, `gradle/libs.versions.toml` | performanceQa·fallback·testBuildType·runner·testApplicationId·test 의존성. 기존 변형/릴리스 정책 유지 |
| `core/data/src/backupProbeNoop/`, `src/performanceQa/` | 같은 internal 관찰 타입의 변형별 구현. 일반 변형은 설정 API 없는 final no-op, performanceQa만 per-instance raw 버퍼/오류 checkpoint 제공. 범용 telemetry·전역 mutable probe는 없음 |
| `BackupEnvelope.kt` | 실제 generateSecret와 GCM init/doFinal 전후 관찰. 기존 암호·순서·AAD·버퍼 소거·예외 매핑 유지 |
| `BackupValidator.kt` | strict UTF-8/JSON parse와 나머지 논리 검증 경계 관찰. 모든 기존 검증 유지 |
| `RoomBackupRepository.kt`, `BackupRestorePlan.kt` | per-instance probe의 내부 생성 경로와 transaction/부분 쓰기/commit 경계. 기존 Hilt 3인자 생성 경로는 no-op에 연결, 공개 BackupRepository/금융 계약 유지 |
| `core/data/src/performanceQa/.../BackupProbeSession.kt` 신규 | internal DB/Repository를 생성하는 이 시험 전용 bridge. public 입구는 해당 계측 artifact에만 있고 일반 release/debug/QA API가 아님. 허용 package·DB prefix 검사 후 합성 seed/대사만 제공 |
| `KeystoreRecoveryCopyStore.kt`, app의 변형별 `RecoveryCopyProbe` | key 접근 전·공간 확인 전·후보 사본 sync 뒤의 인스턴스 checkpoint. 일반 변형에는 오류 설정 API 없음. 기존 Hilt 생성 경로·alias·생성 정책 유지 |
| `app/src/performanceQa/` | 합성 전용 DocumentsProvider와 manifest. 전용 authority·문서 ID, MANAGE_DOCUMENTS/URI grant 경계, 기존 파일/임의 URI 접근 금지 |
| `app/src/androidTest/` | 전용 runner, 성능/장애 시험, 합성 fixture, raw 결과 기록·bounded heap sampler. ViewModel가 필요한 실패 경계만 기존 클래스를 직접 생성 |

bridge/provider/장애 주입 설정 입구가 일반 release/debug/QA 산출물에 없음을 manifest/API로 확인한다. 일반 변형의 probe는 설정·주입 API 없는 no-op 구현이고 전용 변형만 설정 가능한 구현을 갖는다. probe는 성공 값을 만들거나 검증을 생략하지 않는다. 성능 모드의 관찰자는 원시 숫자만 미리 할당한 버퍼에 기록하고 hot path에서 JSON/로그를 만들지 않는다. 실패 주입은 이름이 명시된 장애 시험에서만 활성화한다.

JCA provider 이름은 실제 `generateSecret` 성공 뒤에만 관찰한다. 선조회는 [Android libcore의 SecretKeyFactory 구현](https://android.googlesource.com/platform/libcore/+/d30ce4a7c809da1f168b7ff4d98ea1baa89183d3/ojluni/src/main/java/javax/crypto/SecretKeyFactory.java)에서 failover를 중단하므로 기존 암호 동작을 바꿀 수 있다. host의 임시 합성 provider 거부→정상 provider 대체 회귀로 이 순서를 보호한다. 기기 provider 목록을 변경하는 시험은 아니다.

2026-11-14 첫 S23 smoke의 heap 초과에 따라 PM이 세 가지 보완을 선정했다. 번들 schema의 pattern/Matcher는 `validate` 호출 내부에서만 재사용하고 매 값마다 reset/find하여 기존 언어와 동시 호출 격리를 유지한다. 복원 계측은 암호화된 합성 파일만 사전 준비하고 baseline 뒤 실제 SAF 읽기·inspect·입력 bytes 소거/참조 해제·restore/정리를 포함한다. 제품 복원은 같은 maintenance lease와 사본 검증/CAS 순서를 유지하며 A snapshot·plain 및 정확한 병합 한도 검사의 union을 각각 짧은 helper 수명에 두어 transaction continuation으로 넘기지 않는다. 검증 생략·강제 GC·상한/64MiB 완화는 없으며 실제 감소량과 성공은 새 raw로 확인한다.

두 번째 S23 smoke(head `754d6716`)도 복원 전체137,350,488bytes·같은 전체 baseline을 유지한 restore API 창114,006,280bytes의 동시 추가 표본으로 실패했다. 전체 최대의 세부 단계와 API 최대의 할당 소유자는 확인되지 않았으며 서로 다른 세션/측정창의 차이 전체를 제품 최적화 효과로 해석하지 않는다. 추가 보완은 UTF-8 길이/유효성 검사와 bytes 생성을 분리하는 범위다. 길이는 전체 surrogate 유효성을 검사하며 Long으로 계산하고, 실제 bytes만 정확한 출력 배열 하나에 표준 REPORT encoder로 생성해 encode/flush·입력 소진·출력 길이를 확인한다. strict JSON·오류 우선순위·한도·KDF·복구 사본/원자성은 유지하고, 누적 stream 버퍼나 DOM 파싱 구조는 변경하지 않는다.

합성 입력 파일을 준비한 뒤 read 전 한 번만 관찰을 활성화해 inspect 이후 초기화로 입력 단계 이력을 지우지 않는다. baseline·sampler 전체 창·GC 정책은 유지하며, 종료 후 inspect의 KDF/parse/논리 검증 시작·종료 이벤트가 입력 구간 안에 있는지 확인한다. API26에서 확인한 전용 DocumentsProvider의 projection 무시 결함도 요청 컬럼/순서와 null의 기존 기본값을 지키도록 보완한다. 일반 앱의 SAF 구현은 그대로 두고, FALSE_SIZE/실제 바이트 초과 거부를 포함한 호스트·기기 회귀를 구분한다. 기존 APK·실패 raw는 보존하며 새 head의 실측 전까지 64MiB 해결을 주장하지 않는다.

세 번째 head `9f9d2b9`도 S23 복원 전체106,961,416bytes로 실패했다. 원래 baseline39,946,584bytes로 대조한 입력 B PARSE는100,894,408bytes, RECOVERY_COPY는106,961,416bytes였다. 두 구간을 함께 대상으로 하되 이벤트 겹침을 특정 객체의 실제 소유/생존 증거로 해석하지 않는다. 최소 후속은 실제 SAF 읽기의 bounded segment/EOF 결과 배열, envelope 본문 복사 제거, 내부 사본의 짧은 write/sync 수명과 payload+116 정확 재읽기, 전체 A snapshot 대신 검증된 필요한 fence 값만 사본 작업으로 전달하는 범위다. parser/형식/KDF/한도/전체 측정창/GC 정책은 변경하지 않는다.

segment는 짧은 read마다 만들지 않고 최대8KiB까지 채우며 실제 Long 합계 상한/+1·zero16·interruption·close를 보존한다. 결과 복사 뒤 내부 chunk를 소거하고 read/close 실패에는 반환되지 않은 결과도 소거한다. 사본은 실제 EOF/추가1byte/short·zero를 확인하고 두 번의 GCM 재인증·상수시간 원문 대조·rename/directory fsync/proof를 유지한다. key/decrypt 자체의 예외도 소유 암호문 소거 범위에 포함한다. A helper는 같은 lease/CAS와 전체 strict 검증 후 plain과 필요한 하위 fence 값만 반환하며 B union은 기존처럼 proof 뒤에 수행한다. compiled continuation 검사는 강한 참조 축소의 근거이며 실제 GC/heap 성공 증거와 구분한다.

## 합성 상한과 시간 측정

- 금액·종류·날짜·건수를 고정하고 실제 Repository 경로로 현재 v3의 유효 source snapshot을 생성한다. 실제 생성 경로의 UUID는 run별로 다를 수 있으므로 run 간 byte 동일성을 주장하지 않는다. 같은 run의 20회는 보존한 같은 source bytes/SHA를 사용한다. count는 거래 수가 아니라 manifest의 금융·evidence·operation fence·import·원문 등 전체 recordTotal이다.
- SMALL, RECORD_LIMIT(전체 50,000records), MIXED_LIMIT(정확히 8,388,608bytes·50,000records·원문 합계2MiB)을 제공한다. MIXED_LIMIT은 실제 일반 거래200개·import 지출1개·수동 삭제1개와 그 보호/제출 이력, 나머지 역사 fence를 포함한다. SMALL/RECORD_LIMIT의 기존 금융·import 이력을 버리고 UUID만 성능 입력으로 사용하지 않는다.
- padding은 허용된 memo/원문/표시 이름 안에서 각 필드 한도를 지키며 조절한다. 알 수 없는 필드·무시되는 tail·압축·누락·상한 검사 완화는 금지한다. expected 건수/합계는 상수로 고정하고 run 입력 해시·의미 digest를 보존하며 실제 validator가 먼저 통과해야 측정한다.
- source B와 원래 A의 공개 합성 JSON은 시험 입력으로만 새 UID의 private run 폴더에 보존한다. 제품의 외부 백업·내부 복구 사본은 원래 암호화 경로를 사용한다. 실제 사용자 자료를 평문으로 저장하는 기능을 추가한 것이 아니다.
- 8MiB+1/50,001/원문2MiB+1·33개/depth17 등은 거부 전용 profile이다. 입력 생성/오류 표본을 정상 성능 표본에 섞지 않는다.
- 실제 dispatcher와 monotonic nanosecond clock을 사용한다. 가상 시간·고정 sleep·UI 표시 시각으로 성능을 재지 않는다.

| raw 구간 | 측정/판정 |
|---|---|
| 암호화 KDF / 복호화 KDF | 동일 실제 JCA generateSecret 지점의 각각 20표본. nearest-rank p95 = 정렬한 20개 중 19번째, 각각 ≤3초. 실패/취소를 버리고 좋은 표본으로 바꾸지 않음 |
| KDF·GCM seal·KDF·GCM open·parse·논리 검증 | 각 시작/종료를 한 연속 round에서 수집. provider 이름·알고리즘과 thread도 기록 |
| Repository 전체 호출 | create/inspect/restore의 API 진입·반환과 사전 snapshot/검증도 별도 수집. 암호 시작 전 비용을 숨기거나 서로 다른 round의 값을 빼서 구간을 추정하지 않음 |
| T4 전구간 | 암호화 시작→같은 bytes의 복호화 인증→parse/논리 검증 끝, ≤10초. SAF/fixture 준비는 별도 |
| B10 구간 | 같은 round의 복호화 시작→검증 끝, ≤10초. 추가로 GCM 인증 시작→검증 끝도 기록해 KDF 포함/제외 의미를 실제 raw로 확인 가능하게 함 |
| 복원 transaction | 고정 A/B를 준비한 실제 20회. withTransaction 호출 직전→commit 후 반환을 보수적인 전체 경계로 p95≤10초 판정. queue/본문/commit 반환·준비/복구 사본도 별도 기록 |
| SAF | writeAndRead 전체와 read, 전용 provider의 open/전송/close/reopen 관찰값. OEM provider 지연과 동일하다고 확대하지 않음 |

warmup/초기 cold 값은 별도 표시하고 20개와 섞지 않는다. 환경·프로파일별 raw를 보존하며 기기/캐시/암호화·복호화 표본을 합쳐 유리한 p95를 만들지 않는다. T4/B10은 각 연속 round와 최대값도 남기며 10초 기준을 p95만으로 대체하지 않는다. 반복 사이의 합성 A 재설정·검증은 timed 구간 밖에서만 하고, 자동으로 실제 사용자 장부를 복원하지 않는다.

## heap과 PSS

fixture는 측정 전에 파일/시험 DB로 준비하고 생성용 큰 객체를 유지하지 않는다. 별도 heap 전용 run의 baseline은 입력 파일을 읽고 큰 buffer를 할당하기 전이다. 5ms 목표 sampler가 Java used heap(`totalMemory-freeMemory`), native allocated heap(`Debug.getNativeHeapAllocatedSize`)·동시 합계·실제 sample 간격/최대 gap·GC 횟수를 raw로 수집한다. restore는 read/inspect 이전부터 실제 작업 종료/버퍼 정리까지 포함하고, 이후 시험용 전체 snapshot 대사·해시·JSON 보고서 생성 전에 sampler를 종료한다. 30,000개씩 미리 할당한 primitive array 3개(원소 저장 공간720,000bytes)와 sampler 실행 자원은 baseline에 포함되며 제품 작업 메모리와 구분한다. heap run의 시간은 timing p95에 포함하지 않는다.

추가 heap은 같은 창의 baseline 대비 증가량이다. Java/native 및 동시 합계 관측 최대를 각각 표시한다. 64MiB 초과 관측은 실패다. 샘플 사이의 순간 peak를 놓칠 수 있으므로 **샘플 값이 작다는 사실만으로 실제 peak≤64MiB를 확정하지 않는다**. 긴 gap·GC/임계치 근접·누락은 측정 불충분으로 남기고, 필요할 때 같은 합성 과정의 별도 메모리 프로파일 근거를 QA와 대조한다. 샘플 기반 측정의 한계를 기준 완화로 바꾸지 않는다.

PSS/RSS·native/Java heap은 같은 값이 아니다. PSS는 별도 전후 진단값이며 64MiB heap 판정으로 대체하지 않고 hot loop에서 반복 수집하지 않는다. 근거: [Debug native heap API](https://developer.android.com/reference/android/os/Debug#getNativeHeapAllocatedSize()), [Android 메모리 지표](https://developer.android.com/topic/performance/memory/guide/concepts).

## 장애 입력과 관찰할 불변식

| 격리 입력 | 관찰할 결과 / 대체하지 못하는 것 |
|---|---|
| header/iterations/길이·인증 오류 | KDF 선행 거부 대상은 호출0, 인증 전 parse/금융쓰기0. 유효 v3의 정상 경로와 별도 |
| 실제 JCA 실행 중 취소·제어 gate의 늦은 완료 | 취소 요청과 실제 종료를 분리. active 작업1·늦은 성공0·후속 요청 누적0. gate 지연을 실제 KDF 속도로 세지 않음 |
| 전용 provider open/전송/재읽기 실패·협력 stall | 실제 ContentResolver/PFD 경로의 실패·정리, last verified receipt 유지·거짓 성공0. 합성 stream close 오류와 OEM close 오류는 구분 |
| 내부 사본 key 접근/공간 확인/후보 sync 뒤 오류 | 실제 사본 처리/Repository 경계에서 RECOVERY_COPY, 활성 A와 generation/revision/완료 표식 보존, 평문 fallback0. 실제 alias 삭제/디스크 채우기 금지 |
| 부분 금융 삽입 뒤 예외/SQLiteFullException·commit 전 취소 | 실제 Room transaction 안의 named checkpoint에서 주입. 전후 금융/evidence/fence/초안·세대/표식 digest가 A. 실제 ENOSPC·전원 차단을 실행한 증거는 아님 |
| commit 후 반환 채널 실패 | 정상 commit/메모리 정리 후 전용 checkpoint에서 IOException, 실제 DB 재열기→원래 previewId의 result 조회로 완전한 B 확인·두 번째 사본/교체0 |
| 복원 후 알림 실패·stale context 요청 | 기존 관련 회귀를 유지한다. 이번 전용 기기 시험에서 실제 알림 예약/Doze/재부팅까지 검증했다고 확대하지 않음 |

키 실패는 계측 변형 전용 접근 checkpoint에 주입한다. 정상 경로는 새 performance 앱 자체 UID에서 기존 제품 구현이 전용 key를 최초 생성·사용할 수 있다(PM 기술 선택). 실제 생성은 APK 인계 후 QA가 namespace/UID를 확인한 기기 실행 단계에만 수행한다. 서명키 생성/교체와 기존 앱/QA의 Keystore 접근·삭제·복사·무효화는 금지한다. 합성 key 대역과 실제 AndroidKeyStore 성공은 구분하며 hardware-backed 여부는 관찰값이고 새로운 필수 조건으로 승격하지 않는다. 기존 S23 성공은 새 계측 결과가 아니다.

## 실행·검증·인계

Android는 먼저 observer no-op 전후 golden bytes/오류·호출 순서·기존 원자 복원/취소 회귀를 확인한다. 신규 probe/통계/fixture self-check도 검증한다. Kotlin만 작성하고 ktlintFormat 뒤 별도 ktlintCheck를 실행하며 docs/README는 자동 포매팅하지 않는다.

전용 빌드는 `:app:assemblePerformanceQa`, `:app:assemblePerformanceQaAndroidTest`다. task 존재·targetPackage·certificate·manifest/provider·계측 bridge의 일반 변형 제외·APK SHA를 실제 확인한다. debug의 기존 host test 활성화를 명시적으로 유지하고, performanceQa host task는 새 전용 테스트만 선택해 기존 전체 debug 회귀와 중복 실행하지 않는다. CI에 전용 host tests·두 APK 컴파일·전용 Lint를 추가한다. 실행 상태와 재현 방법은 [QA 실행·인계](backup-instrumentation-runbook.md)에서 확인하며 CI 성공을 기기 성능 성공으로 보고하지 않는다.

raw 결과는 schemaVersion/runId/source head/두 APK SHA·cert/fixture SHA·bytes·recordCounts/SDK·model·provider·thread/단계시간/heap raw와 gap/표본 상태/원자성 digest를 포함한다. 비밀번호·key bytes·평문·memo·원시 URI·기기 serial은 출력하지 않는다. Android 기기 조작과 합성 파일 전송은 QA만 담당한다.

설치 전후 기존 QA·backupQa의 APK/UID/저장영역/초안 보존을 QA가 대조한다. 시험 오류 시 해당 run만 중지하고 마지막 A/B snapshot·영속 결과를 조회하며 자동 재실행/전체 초기화를 하지 않는다. 정리는 닫힌 전용 DB·run directory만 경로 확인 후 수행할 수 있고 실제 앱/기존 QA·키·외부 성공 백업은 정리 대상이 아니다. 정상·실패 모두 raw/암호화 합성 증거를 보존한다.

API26, 대표 API33+의 실제 JCA/Unicode/provider/Keystore·OEM SAF 및 낮은 성능 기기는 S23/API36 또는 Robolectric SDK28로 대체할 수 없다. API26 에뮬레이터를 확보하더라도 API 동작과 실기기 성능/OEM 보장은 분리한다. 환경 설치·실기기 key 준비·재부팅/시간 변경/Doze는 이번 설계 읽기나 APK 빌드에 포함하지 않는다.

**선정과 남은 검증:** 위 최소 구현·관련 회귀·커밋/PR·정확 head CI 후 QA에 인계한다. non-debuggable 동일 서명 계측의 실제 기기 실행은 QA 결과로 확인하며 실패를 숨기기 위해 debuggable을 바꾸지 않는다. 유효 상한 미확보·측정 불충분·목표 초과는 PASS로 처리하거나 KDF/한도를 바꾸지 않고 근거와 다음 조치를 인계한다. 절대 peak heap/API26/OEM 등 미입증 조건, 실제 Notion 반영·배포 보류는 유지한다.
