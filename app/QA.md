# 격리된 QA 앱

S23에서 합성 금융 자료를 입력하기 위한 debug 성격의 별도 앱이다. 제품 Kotlin·Compose·Room·Hilt·장부 진입/저장/복구 경로를 그대로 사용한다. 자동 fixture·기존 DB 복사·원격 연결은 추가하지 않는다.

| 항목 | 기본 debug / release | QA |
| --- | --- | --- |
| Gradle buildType | debug / release | qa (debug 설정 복제) |
| applicationId | com.monit.app | com.monit.app.qa |
| 앱 이름 | MONIT | MONIT QA |
| 서명 | debug 키 / 현재 release 미서명 | debug 키 |
| 제품 소스 | app/src/main 및 기존 모듈 | 동일 소스, 라이브러리는 debug fallback |
| 추가 source set | 없음 | app/src/qa의 앱 이름 리소스만 추가 |
| Room 파일 이름 | monit-ledger.db | monit-ledger.db |

QA 앱은 다른 applicationId를 사용하고 sharedUserId나 앱 간 DB 접근을 선언하지 않는다. 같은 DB 파일 이름도 [앱별 UID로 분리되는 내부 샌드박스](https://source.android.com/docs/security/app-sandbox)에 속한다. Room은 기존 ApplicationContext로 파일을 열며 저장 경로·스키마·계산·쓰기 동작을 분기하지 않는다. 새로 설치한 QA 앱에서 QA가 실제 UI로 합성 계좌/거래를 입력한다. QA 앱을 이전에 사용했다면 그 QA 데이터는 유지되므로 자동으로 빈 DB라고 가정하지 않는다.

## 복원 전용 앱과 현재 기기 경계

main에는 debug 설정을 복제한 `backupQa`도 있다. `com.monit.app.qa.backup` / `MONIT 백업 검증`으로 별도 UID·DB를 사용하며 `:app:assembleBackupQa`로 빌드한다. 복원은 전체 장부를 교체하고 UI 초안 payload를 제외하므로 원래 `.qa`의 자료·초안 보존을 이 백업으로 대신하지 않는다. 기존 `.qa`와 `.qa.backup` 자료를 삭제·초기화하지 않는다.

`performanceQa` / `.qa.performance`는 [PR #73](https://github.com/1971123-seongmin/MONIT/pull/73)의 계측 지원이며 이 문서의 기준 main에는 아직 없다. CI 성공과 기기 성능시험 미실행을 구분한다. 사용자의 최근 앱 카드 육안 확인 중에는 기기 제어를 보류하고 확인 응답·인계 후 이미 승인된 QA를 재개한다. 지정 기기는 `SM-S911N`뿐이다. [현재 검증 상태](../docs/README.md#검증과-남은-일)를 따른다.

## 빌드와 전달

```sh
./gradlew :app:assembleQa
```

출력은 app/build/outputs/apk/qa/app-qa.apk다. Android Studio Build Variants에서도 app의 qa를 선택할 수 있다. 기존 assembleDebug·assembleRelease는 기존 패키지와 이름을 유지한다. CI assemble 검사에는 debug와 qa를 함께 포함하며 기존 필수 검사를 유지한다.

설치·실행은 QA가 담당한다. 설치 전 APK의 applicationId·MONIT QA 이름·서명·SHA256을 전달받은 값과 대조하고, 기존 com.monit.app을 제거하거나 데이터를 지우지 않는다. 기본 debug APK 대신 QA APK를 선택한다. 실제 공존 설치·기기 UID/내부 DB 분리·IME·입력·중복/응답 유실 복구·합계는 실기기 시험으로 확인하며 APK 정적 검사나 빌드 성공만으로 통과 처리하지 않는다.

QA 상태를 초기화해야 한다면 QA 앱만 대상으로 별도 승인된 절차를 따른다. 기존 앱의 DB 복사·이관, 앱 간 자료 공유, 자동 초기화는 없다. 테스트 금융 자료를 공개 로그·문서·저장소에 남기지 않는다.

구성 방식은 Android의 [buildType·applicationIdSuffix·matchingFallbacks](https://developer.android.com/build/build-variants)를 따른다. 최신 APK hash·서명·검증 결과는 해당 PR과 PM/QA 인계에서 확인한다. CodeRabbit은 지속 OFF이며 필수 CI와 직접 변경 검토는 유지한다.
