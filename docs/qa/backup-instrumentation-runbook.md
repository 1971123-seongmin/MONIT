# 백업·복원 계측 QA 실행·인계

Issue [#72](https://github.com/1971123-seongmin/MONIT/issues/72), [설계·측정 기준](backup-instrumentation-plan.md). Android는 빌드/정적 검사/호스트 회귀를 담당하고 QA만 기기를 조작한다. 실제 Notion 반영·배포는 보류한다. 아래 명령은 QA의 재현용이며 이 문서 작성 과정에서 기기에 실행하지 않았다.

## 대상과 실행 전 확인

| 항목 | 고정 값 / 확인 |
|---|---|
| target | `com.monit.app.qa.performance`, performanceQa, debuggable=false |
| test / runner | `com.monit.app.qa.performance.test/com.monit.app.backup.probe.BackupProbeRunner` |
| APK | `app/build/outputs/apk/performanceQa/app-performanceQa.apk`, `app/build/outputs/apk/androidTest/performanceQa/app-performanceQa-androidTest.apk` |
| 서명 | 기존 개발용 인증서. 두 APK의 apksigner SHA256 동일 여부를 실제 확인하고 인계 값과 대조 |
| 입력 | UUID별 자체 DB·run directory. 공개 합성 JSON만 seed에 사용하며 기존 앱/QA/backupQa DB·초안·키·외부 파일 접근 없음 |
| 기기 | QA가 기존 앱·QA·backupQa의 식별자/UID/자료 보존과 새 performance namespace를 확인. 이미 performance namespace가 있으면 임의 덮어쓰기·clear-data 없이 기존 실행 증거와 충돌 여부를 먼저 확인 |

PM/QA의 기기 사용 보류가 끝난 뒤에만 설치/실행한다. 기존 서명키 생성/교체나 기존 UID의 Keystore 접근·삭제·복사·무효화는 하지 않는다. 새 performance UID의 정상 내부 복구 사본 경로만 전용 key를 최초 생성·사용할 수 있다. runner는 plain Application을 사용하며 기본 제품 DB/알림 observer·Activity를 실행하지 않는다.

## 빌드와 호스트 검증

```text
./gradlew ktlintFormat
./gradlew ktlintCheck
./gradlew :core:model:test testDebugUnitTest :core:data:testPerformanceQaUnitTest :app:testPerformanceQaUnitTest
./gradlew :app:assembleDebug :app:assembleQa :app:assembleBackupQa :app:assembleRelease :app:assemblePerformanceQa :app:assemblePerformanceQaAndroidTest lintDebug :app:lintPerformanceQa
node --test .github/scripts/ci-impact.test.mjs .github/scripts/backup-probe-artifacts.test.mjs
```

Gradle 동시 프로세스를 띄우지 않는다. docs/README는 자동 포매팅 대상에서 제외한다. 일반 네 변형의 manifest에 전용 provider가 없고 DEX에 `BackupProbeSession`, `BackupProbeFixtures`, `ProbeKdfGate`, `ProbeFailure`, `ProbeDocumentsProvider`, `ProbeDocumentMode`, `ProbeStatistics`가 없음을 확인한다. 일반 probe class의 API에는 `configure`, `snapshot`, `failurePoint`, `injected`가 없어야 한다. debug 격리 테스트와 release/debug/QA/backupQa 실제 APK 검사를 함께 사용한다.

## 실제 기기 실행

QA는 인계받은 두 APK의 SHA256·source head를 `$targetSha`, `$testSha`, `$sourceHead`에 넣고 전용 serial을 `$serial`로 명시한다. 아래 예시는 PowerShell이다. 임의 APK 재빌드/재서명 후 이전 SHA로 진행하지 않는다. `connectedPerformanceQaAndroidTest`로 전체를 무조건 실행하지 않고 항상 class를 선택한다.

```powershell
$runner = 'com.monit.app.qa.performance.test/com.monit.app.backup.probe.BackupProbeRunner'
$performanceClass = 'com.monit.app.backup.probe.BackupPerformanceTest'
$faultClass = 'com.monit.app.backup.probe.BackupFaultInstrumentationTest'

# 연결·동일 서명·non-debuggable·동작 점검. smoke는 성능 합격 증거가 아니다.
adb -s $serial shell am instrument -w -r -e class $performanceClass -e smoke true -e sourceHead $sourceHead -e targetApkSha $targetSha -e testApkSha $testSha $runner

# 장애/원자성 시험은 실제 플랫폼 경로를 사용하며 timing 표본에 섞지 않는다.
adb -s $serial shell am instrument -w -r -e class $faultClass -e sourceHead $sourceHead -e targetApkSha $targetSha -e testApkSha $testSha $runner

# 같은 환경에서 crypto 20회와 실제 restore 20회를 수집한다. 실패 표본을 버리거나 대체하지 않는다.
adb -s $serial shell am instrument -w -r -e class $performanceClass -e sourceHead $sourceHead -e targetApkSha $targetSha -e testApkSha $testSha $runner
```

성능 class의 두 메서드는 각각 별도 유효 MIXED_LIMIT 입력/runId를 만든다. crypto warmup2, restore warmup1 후 20회이며 heap은 별도1회다. restore 각 sample은 새 A DB를 사용한다. 암호화된 합성 입력 파일은 사전 준비하고 baseline 뒤 실제 `BackupDocuments.read`·inspect·암호문 소거/참조 해제·restore/정리를 측정한다. `inputReadStartNanos`·`inspectEndNanos`와 준비/측정 경계 표시도 raw에 남긴다. B의 예상값은 전체50,000records·유효 거래201개·삭제1개·income200·expense5000과 전체 의미 digest다. 단순 transaction 수만 대사하지 않는다.

관찰 초기화는 합성 파일 준비 후 baseline/read 전에 한 번 수행한다. inspect 뒤에는 초기화하지 않으며, sampler 종료 후 입력 구간의 KDF/parse/논리 검증 시작·종료 이력이 모두 남았는지 확인한다. restore API 구간을 따로 분석해도 최초 전체 baseline을 그대로 사용하고 기준값을 다시 잡지 않는다. 전용 provider의 부분/재정렬/null projection과 FALSE_SIZE는 컬럼 이름으로 대조하며, metadata와 별개로 실제 8MiB+68 초과 bytes 거부를 유지한다.

새 DB·암호화 복구 사본을 sample별로 보존하므로 누적 저장 공간이 필요하다. QA는 smoke의 파일 크기/여유 공간을 기준으로 20회 및 기존 증거 보존 여유를 먼저 대조한다. 부족한 기기에서 반복을 강행하거나 디스크를 채우지 않고 준비 미충족으로 남긴다. raw header의 `usableSpaceBeforeBytes`는 준비 후의 진단값이며 공간 전체 보장의 대체값은 아니다.

KDF gate는 JCA 직전의 통제된 blocking 구간이며 실제 provider 내부 취소 성능이나 즉시 선점의 증거가 아니다. SQLFull/Key 접근/사본 오류는 명시 checkpoint의 주입 오류로, 실제 ENOSPC·key 무효화·OEM 장애를 재현했다고 보고하지 않는다. commit 후 시험은 파일 DB 재열기를 검증하며 실제 프로세스 강제 종료/전원 차단과 구분한다.

## raw 회수와 판정

출력의 `probeRunId`와 `probeArtifact`를 보존한다. non-debuggable 앱이므로 `run-as`를 요구하거나 debuggable을 바꾸지 않는다. 같은 서명의 계측 APK가 지정한 자체 run의 JSONL만 읽어 상태 채널로 전송한다. key·DB·합성 원문은 export 대상이 아니다.

```powershell
$exportClass = 'com.monit.app.backup.probe.ProbeArtifactExportTest'
adb -s $serial shell am instrument -w -r -e class $exportClass -e artifactRunId $runId $runner > export.log
node .github/scripts/backup-probe-artifacts.mjs export.log NEW_EVIDENCE_DIRECTORY
```

회수기는 파일 이름·UUID·chunk 순서/누락·16MiB 상한·byte 수·SHA256을 확인하고 새 디렉터리에만 쓴다. 실패한 시험의 기존 raw도 동일 runId로 회수할 수 있다. 종료 summary가 없는 raw는 중단된 증거이며 정상 완료로 처리하지 않는다. 원래 기기 파일은 자동 삭제하지 않는다.

raw에는 source head(인계 입력임을 명시), 설치된 두 APK SHA256·공개 인증서 SHA256·UID·SDK/model, 입력 SHA/정확 bytes/recordCounts, monotonic event/thread/provider, warmup/timing/heap 구분, 실패/NOT_RUN, baseline·실측 gap·GC와 표본 heap 값이 남는다. 비밀번호·key bytes·메모·원시 URI·기기 serial은 raw에 쓰지 않는다.

- timing 20개가 완전할 때만 nearest-rank p95(정렬된19번째)를 산출한다. 암호화/복호화 KDF 각각 p95≤3초, 각 round T4(암호화 시작→복호화/전체 검증 완료)·B10(CRYPTO→전체 검증 완료)≤10초, restore transaction p95≤10초를 대조한다. 관찰 overhead 추정은 별도 값이며 시간을 빼서 유리하게 보정하지 않는다.
- heap은 5ms 목표의 Java/native 할당 표본이고 PSS는 전후 진단값이다. baseline 대비 동시 합계64MiB 초과 관측은 실패다. 낮은 관측값도 절대 peak≤64MiB를 입증하지 못하므로 `heapGate=NOT_PROVEN_BY_SAMPLING`과 별도 메모리 근거 필요를 유지한다. 보고서/전체 대사 할당은 sampler 종료 후다.
- `SMOKE_ONLY`, `INCOMPLETE`, `TIMING_TARGET_EXCEEDED`, `OBSERVED_HEAP_LIMIT_EXCEEDED`를 합격으로 취급하지 않는다. 정상 timing이어도 `TIMING_RECORDED_HEAP_EVIDENCE_PENDING`이며 `wholeReleaseAccepted=false`다. framework의 테스트 성공 수만으로 출시 승인을 주장하지 않는다.
- API26과 대표 API33+·낮은 성능 기기·OEM SAF/권한 회수/close·실제 kill/reboot/time/Doze·절대 peak heap은 별도 QA/영향 계획으로 확인한다. S23/API36 또는 Robolectric SDK28 결과로 대체하지 않는다.

## 검증 상태

첫 S23/API36 smoke(head `7b5b51a`, 각1표본)는 복원·crypto 모두 `OBSERVED_HEAP_LIMIT_EXCEEDED`였다. 원래 두 APK와 실패 raw는 동결 보존한다. 복원 run `3de6a951-787c-4d7c-bdb4-dac87e0ab930`의 같은 표본 Java+native baseline 대비 최대는196.541MiB, crypto run `6a2c8f10-f368-4c60-8bba-03eaed7fd401`는166.478MiB다. 독립 최대값을 합한 값이 아니며 20회 p95도 아니다. 복원 최대는 실제 restore API 전 준비 구간에 있지만 API 창만 잘라도86.497MiB가 관측되어 제품 수명도 함께 보완한다. 새 head의 동일 기준 실측 전까지 한도 해결·출시 수용을 주장하지 않는다.

두 번째 head `754d6716`의 복원 run `e4c37b6a-5fb6-45fd-8c00-f57199519c91`도 전체137,350,488bytes·원래 전체 baseline을 유지한 API 창114,006,280bytes의 동시 추가 표본으로 실패했다. crypto run `7dc793b0-a51a-436e-b834-d5c5d99f1670`는59,285,384bytes의 단일 smoke 미초과이며 절대 peak/20회 수용 증거가 아니다. 최대 표본의 정확한 할당 소유자는 미확정이고, 첫 세션과 두 번째 세션의 실행 방식/측정창이 달라 차이 전체를 제품 효과로 환산하지 않는다. 해당 두 APK·raw를 동결 보존하고 UTF-8 검사/출력 분리와 이벤트 초기화 보완 후의 정확한 새 head에서 다시 판정한다.

같은 head의 API26 SMALL SAF 시험은 전용 provider가 COLUMN_SIZE projection을 무시해 UUID를 Long으로 읽는 오류로 실패했다. 요청 projection/순서·null 기본값 보완과 컬럼 이름 기반 assertion을 적용하며, 이 시험 provider의 결함을 제품 금융 데이터의 오류로 확대하지 않는다. 호스트 cursor 계약 회귀와 새 APK의 실제 API26 결과는 별도 근거로 기록한다.

이 후속 보완의 호스트 시험은626개(모델28·debug578·전용20), 실패/오류/skip 0이다. UTF-8 계약8개와 API26 host provider 계약6개를 추가했으며, provider 회귀는 수정 전 projection 불일치5개 실패·기존 null 기본값1개 통과를 확인하고 수정 후6개 모두 통과했다. ktlintFormat 뒤 별도 ktlintCheck, debug/qa/backupQa/release/performanceQa·계측 APK 빌드, 전체 lintDebug와 전용 Lint를 통과했다. 일반 APK 네 종의 DEX/manifest 격리, compiled no-op API, 새 두 APK의 동일 서명·runner/targetPackage·non-debuggable도 확인했다. 호스트 통과는 실제 기기 메모리/API26 수용을 대신하지 않는다.

구현·호스트 검사·APK 격리/서명 검증 및 최신 head CI의 최종 결과는 연결 PR과 PM 인계에서 정확한 커밋/SHA로 기록한다. 실제 기기 실행은 Android에서 수행하지 않았으며 QA 담당이다. PR #71 작업 공간/APK와 기존 데이터는 보존한다.
