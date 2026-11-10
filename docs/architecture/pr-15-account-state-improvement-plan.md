# PR #15 계좌 등록 상태 계층 개선 설계

- 기준일: 2026-11-10 (Asia/Seoul)
- 상태: 승인된 설계를 PR #15에 구현. 현재 동작과 복구 범위는 [상태 계층 계약](../../feature/accounts/STATE.md)을 따른다.
- 기준 main: `21f6f86ecbd139cc0c2e51efdb2f6f72234020e7` (개발 계획 MR #20 병합 포함)
- 근거: [개발 계획](monit-modules-di-ui-plan.md), [현재 ViewModel](../../feature/accounts/src/main/kotlin/com/monit/feature/accounts/AccountRegistrationViewModel.kt), [현재 상태 모델](../../feature/accounts/src/main/kotlin/com/monit/feature/accounts/AccountRegistrationState.kt), [저장 계약](../../core/data/README.md).

## 1. 이번 개선의 목적과 범위

Google 권장 아키텍처의 `UI → ViewModel → Repository → 데이터 소스` 흐름을 유지한다. 계좌 등록을 위해 domain 모듈·UseCase·범용 MVI 기반 클래스를 추가하지 않는다. 데이터 일관성은 Repository가 보장하고 ViewModel은 입력·화면 상태를 조정한다.

이번 설계는 PR #15의 계좌 상태 계층과 필요한 DI 정리에 집중한다. 전체 모듈 이동, 거래·달력 구현, 실제 계좌 화면, 서버 동기화, 암호화 라이브러리 도입은 별도 작업이다. 일반 개발 계획의 `data:ledger` 재편을 이 PR에 함께 넣지 않는다. 현재 `core:model`의 Repository 인터페이스 위치도 구조 이동 단계 전까지 유지한다.

기존 금융 저장 동작을 보존하면서 책임을 분리하고 조회 전용 동선을 추가한다.

## 2. 유지할 동작

- 첫 유효 제출에서 operation·account·anchor ID를 한 번 생성하고 원문 입력을 고정한다.
- 저장 중 중복 탭·입력 변경을 막고, 별개의 새 등록에는 새 ID를 사용한다.
- 응답 유실·재진입은 기존 영수증부터 확인한다. 예외만으로 저장 실패/성공을 추정하지 않는다.
- 저장 성공과 snapshot 조회 실패를 분리한다. 조회 재시도가 금융 재저장을 유발하지 않는다.
- 서울 기준 1900-01-01~어제, 기존 Unicode/UTF-8 검증, 미확인 잔액 null과 사유를 유지한다.
- context·storageGeneration·영수증 참조 검증, 취소 전파, 늦은 조회가 새 입력을 덮어쓰지 않는 처리를 유지한다.
- Room 파일명·schema v1·SQL·내부 요청 bytes/해시·트랜잭션·영수증의 의미는 변경하지 않는다.

## 3. 개선 근거

| 현재 확인한 구조 | 제안과 이유 |
| --- | --- |
| ViewModel에 입력 검증·모델 변환·제출 직렬화가 함께 있다. | 순수 validator·UI mapper·제출 스냅샷/codec을 구분한다. 분리 이유는 파일 수가 아니라 각 규칙의 단독 검증과 변경 범위 제한이다. |
| `phase`, `problem`, `savedAccountId`, `readPhase`가 독립 필드다. | 상태별 필수 값을 타입이 소유하도록 해 저장 성공인데 ID가 없는 조합을 줄인다. 저장 상태와 조회 상태는 계속 분리한다. |
| `Retry`가 영수증 조회와 재저장을 함께 수행할 수 있다. | 결과 조회만 하는 `CheckResult`와, 기존 결과 확인 후 같은 요청을 재전달할 수 있는 `RetrySave`를 구분한다. 버튼 의미와 실제 쓰기 권한을 일치시킨다. |
| ArrayList의 열 개 위치로 제출을 복원한다. | 명명된 필드의 버전 있는 codec을 두고 검증을 중앙화한다. feature 밖으로 이 형식을 공개하지 않는다. |
| 영수증 검증이 ViewModel 안에 있다. | 현재 저장 계약의 검증을 순수 함수로 먼저 분리한다. 같은 검증이 다른 소비자에도 필요해질 경우에만 Repository 계약으로 이동한다. |
| Hilt 한 모듈이 DB·Clock·Repository를 직접 제공한다. | 생성자 주입 가능한 구현체와 인터페이스 바인딩, DB/Clock 팩토리의 책임을 구분한다. data source 래퍼나 UseCase를 추가하지 않는다. |
| 실제 장부 context를 제공하는 앱 진입점이 없다. | 없거나 잘못된 context에서 쓰기를 막는 현재 동작을 유지한다. 영속 bootstrap은 별도 계약을 준비할 때 연결한다. 임의 ID/샘플로 제품 동선을 꾸미지 않는다. |

## 4. 파일별 책임과 변경 범위

경로는 저장소 상대 경로다. 승인된 구현 범위는 아래와 같다.

| 파일 | 제안하는 책임/변경 |
| --- | --- |
| `feature/accounts/src/main/kotlin/com/monit/feature/accounts/AccountRegistrationState.kt` | `AccountDraft`, 입력 오류, Action과 불변 UiState. 저장/조회 상태의 필수 값 및 조회/재저장 Action을 구분한다. |
| `feature/accounts/src/main/kotlin/com/monit/feature/accounts/AccountRegistrationViewModel.kt` | Main 이벤트 직렬 처리, 캡처된 제출의 소유, Repository 호출, 결과를 화면 상태로 조정. 공통 codec/mapper/검증 구현은 밖으로 옮긴다. |
| `feature/accounts/src/main/kotlin/com/monit/feature/accounts/model/AccountUiModel.kt` | Room Entity가 아닌 `LedgerAccount`와 검증된 balance 입력에서 표시 모델로 변환. balance map에 키가 없는 경우를 미확인 null과 혼동하지 않는다. |
| `feature/accounts/src/main/kotlin/com/monit/feature/accounts/model/AccountDraftValidator.kt` | 순수 입력 검증. 원문을 trim·날짜 자동 보정하지 않고 첫 필드 오류를 반환한다. Clock에서 계산한 서울 오늘을 입력받는다. |
| `feature/accounts/src/main/kotlin/com/monit/feature/accounts/model/SubmittedAccountState.kt` | context·operationId·typed CreateAccount의 불변 제출 스냅샷. 저장 의도가 생긴 뒤에는 입력과 분리한다. |
| `feature/accounts/src/main/kotlin/com/monit/feature/accounts/model/AccountSubmissionCodec.kt` | SavedStateHandle 경계의 인코딩/복원, 버전·필수 필드·정규 ID·context 검증. raw DB requestJson은 해석하지 않는다. |
| `feature/accounts/src/main/kotlin/com/monit/feature/accounts/model/AccountReceiptValidator.kt` | operationId·계좌/기준점 참조·객체 revision의 일치 판정. UI 상태나 Repository 호출은 하지 않는다. |
| `core/data/src/main/kotlin/com/monit/core/data/RoomLedgerRepository.kt` | DI 단계에서 생성자 주입에 필요한 어노테이션만 추가한다. SQL·원자성·동작 변경은 하지 않는다. |
| `core/data/src/main/kotlin/com/monit/core/data/LedgerDataModule.kt` | 새 binding으로 제공을 이전한 뒤 제거한다. 같은 타입의 옛/새 제공을 중복 유지하지 않는다. |
| `core/data/src/main/kotlin/com/monit/core/data/di/LedgerDatabaseModule.kt` | 기존 DB 이름·설정의 Singleton 제공. 새 암호화 설정이나 destructive fallback을 추가하지 않는다. |
| `core/data/src/main/kotlin/com/monit/core/data/di/LedgerRepositoryModule.kt` | 기존 LedgerRepository 인터페이스와 Room 구현의 Singleton `@Binds`. |
| `core/data/src/main/kotlin/com/monit/core/data/di/LedgerClockModule.kt` | 기존 서울 Clock 제공과 고정 Clock 테스트 대체 경계. |
| `feature/accounts/src/test/kotlin/com/monit/feature/accounts/AccountRegistrationViewModelTest.kt` | 공개 상태/호출 결과를 기준으로 기존 시나리오 유지, 조회 전용 Action과 복원·지연 응답을 검증한다. |
| `feature/accounts/src/test/kotlin/com/monit/feature/accounts/AccountRegistrationModelsTest.kt` | validator·mapper·codec·receipt 검증의 경계 시험. 구현을 그대로 재서술하는 테스트는 추가하지 않는다. |
| `feature/accounts/STATE.md` | 실제 구현과 복구 보장 범위를 갱신한다. |

기존 core/model·app·Room schema는 변경하지 않는다. feature 단위 테스트에는 기존 Version Catalog의 Robolectric과 Android 리소스 지원을 연결해 Bundle/Parcel 경계를 검증한다.

## 5. 상태와 사용자 Action

저장 상태는 `Initializing`, `Editing`, `InitializationFailed`, `Saving(submission)`, `CheckingResult(submission)`, `Unconfirmed(submission)`, `Saved(operationId, accountId)`, `Unavailable(reason)`이다. 초기화 실패 재시도는 저장 미확정과 구분한다. 조회 상태는 별도로 `NotRequested`, `Loading`, `Ready(account)`, `Failed`를 둔다.

| Action | 허용 상태와 관찰 결과 |
| --- | --- |
| 입력 변경 | Editing에서만 원문 변경. 해당 오류를 해제하며 UUID/쓰기 호출 없음. |
| Submit | Editing + 유효 입력일 때만 캡처/ID 생성. SAVING을 동기적으로 먼저 반영해 연타 차단. |
| CheckResult | 미확정 제출의 영수증만 조회. 결과가 없어도 createAccount를 호출하지 않음. |
| RetrySave | 미확정 제출을 사용자가 명시 재시도. 먼저 영수증 확인, 있으면 검증 후 기존 성공 사용, 없을 때만 같은 ID·값으로 쓰기. 조회 실패 시 쓰지 않음. |
| 초기화 재시도 | 초기화만 재실행. 복원된 제출이 있으면 결과 조회부터 수행. |
| ReloadSavedAccount | 확정된 저장의 snapshot 조회만 재시도. 저장 상태/ID는 유지. |
| StartNew | 저장 확정 후 명시적으로 새 입력 시작. 이전 snapshot의 늦은 성공/실패/취소는 새 상태를 변경하지 않음. |

상태 타입의 이름/배치는 위 의미를 유지하는 범위에서 구현 시 정리한다. 화면 이동·Snackbar·실제 Screen은 이번 PR 개선의 완료 조건에 포함하지 않는다. 화면 연결 때에는 저장 영수증과 UI 효과 소비를 분리한다.

## 6. 복원과 오류 처리

- codec은 필드 이름을 사용하는 Bundle 기반 v2 스냅샷을 사용한다. Room 요청 형식과 다른 화면 복원 형식이다. 기존 v1 ArrayList를 읽는 작은 변환 경계는 두어 이미 존재하는 task의 operationId와 값을 보존하고, 정상 복원 후 v2로 기록한다.
- v1/v2 모두 필드 누락·타입/버전 오류·다른 context·잘못된 ID를 거부한다. 손상된 값을 기본 입력이나 새 ID로 바꿔 재제출하지 않는다. 알 수 없는 버전은 명시적 복구 불가 상태다.
- 화면 재생성에서는 성공한 제출도 영수증부터 확인한다. 성공 결과를 확인하면 같은 계좌 ID를 제공하고 새 쓰기를 하지 않는다.
- task가 사라지면 SavedStateHandle만으로 복원하지 못한다. Room pending 복구·영속 초안·장부 bootstrap은 별도 계약이 필요한 후속 범위이며 이번 개선으로 지원 완료라고 표시하지 않는다.
- 저장 예외는 기존 결과를 먼저 조회한다. 영수증 확인 실패는 미확정으로 남긴다. 확정된 저장 후 조회 오류/취소는 조회 실패로만 처리한다.
- `CancellationException`은 전파한다. 광범위한 예외를 빈 결과·성공·다른 제출의 성공으로 바꾸지 않는다.
- 금융 충돌 상태에서는 새 제출/수정/자동 덮어쓰기를 제공하지 않는다. 충돌 종류별 화면 복귀와 입력 수정 가능 여부는 지원 API에 맞춰 후속 설계한다.
- 큰 이스케이프 문자열의 정규화 요청 크기 P2는 Backend 결정 전 임의 상한이나 오류 코드로 해결하지 않는다. 같은 결정적 거부를 재시도하면 성공한다고 약속하지 않는다.

## 7. 구현 순서와 검증

1. **순수 책임 분리:** 모델·validator·mapper·codec·영수증 검증을 추출한다. 기존 Action/동작을 먼저 보존해 원인 범위를 제한한다.
2. **상태/Action 정리:** 불가능한 필드 조합을 줄이고 결과 확인과 재저장을 분리한다. 초기화/조회/저장의 실패 경계를 각각 확인한다.
3. **DI 정리:** 기존 provider를 생성자 주입·binding·DB/Clock 제공으로 옮긴다. 새 data/domain 모듈은 만들지 않는다.
4. **계약 문서/회귀 확인:** STATE.md를 실제 동작과 맞추고 다음 기능에 필요한 계약 미비를 별도로 남긴다.

검증 대상은 기존 계좌 18개 시나리오, 모델·Room·디자인 시스템 기존 검증, 앱 Hilt 빌드·관련 Lint다. 새 테스트는 아래 사용자 관찰 결과에 집중한다.

- CheckResult 연타/영수증 없음/조회 실패에서 금융 쓰기 횟수 0.
- RetrySave의 같은 operation·account·anchor·payload, 중복 Retry 차단, 별개 같은 이름의 등록은 다른 ID.
- v1→v2 및 v2 재생성에서 값/ID 보존. 누락·틀린 타입·알 수 없는 버전·다른 generation은 쓰기 없음.
- 올바른 operationId지만 다른 계좌/기준점 참조인 영수증은 성공으로 수용하지 않음.
- 저장 성공 뒤 snapshot 실패·취소와 재조회가 저장 상태/계좌 ID를 보존.
- StartNew 후 이전 조회의 성공/실패/취소가 새 입력을 덮어쓰지 않음.
- Hilt 제공 누락/중복 없음, 기존 DB 파일 재열기·금융 원자성/재시도 보장 유지.

테스트에는 한국어 시나리오 문서화를 적용한다. 계좌 테스트 28개를 포함한 전체 77개가 실패·오류·건너뜀 없이 통과했고 Debug 빌드·Lint도 통과했다. 변경하지 않은 모듈의 UP-TO-DATE 결과도 포함한다. 기기 수명주기 검증과 원격 CI·독립 리뷰 결과는 PR에서 별도로 확인한다.

## 8. 리뷰 기준

최신 PR head의 CI와 실제 독립 리뷰 내용을 확인한다. 자동 리뷰 설정은 변경하지 않으며 새 코드에 대한 전체 리뷰가 필요하면 `@coderabbitai full review`를 요청한다. 검토 명령에 대한 응답만으로 코드 리뷰 완료를 표시하지 않는다. EGM 독립 검토는 실행하지 않았다.

## 9. 완료로 표시하지 않는 항목

전체 모듈 이동·UseCase/domain 도입·제품 화면/Prevue 렌더·실기기 QA·서버 동기화·영속 초안·키 보호/DB 암호화는 이 계좌 상태 개선과 별도다. Keystore/DB 암호화는 일반 개발 계획의 검토안이며 이번 설계로 라이브러리를 선정하거나 데이터 저장 방식을 바꾸지 않는다.

CodeRabbit 명령의 근거: [공식 리뷰 명령](https://docs.coderabbit.ai/reference/review-commands).
