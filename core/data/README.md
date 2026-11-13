# 로컬 저장 기반

schema7의 기준액 자체 revision/CAS·변경 이력·확정 충돌과 명령 v4는 [계좌·자산 계약](../../feature/accounts/ASSETS.md)을 따른다. 기존 생성/거래의 두 단계 writer를 재사용하며 납입 v3의 별도 intent 경계는 유지한다.

schema 6의 알림 선호·dirty·일별 요약 식별자와 비파괴 v5→v6, 금융/OS 분리·향후 복원 경계는 [알림 상태](../../feature/home/REMINDERS.md)를 따른다. OS 권한·예약은 데이터 계약의 성공 결과와 구분한다.

현재 거래 수정·논리 삭제는 [거래 상태](../../feature/transactions/STATE.md), 신규 schema 5의 월별 납입·단일 outer writer·원래 intent/명시 해제 경계는 [납입 상태](../../feature/home/STATE.md)를 따른다. 아래 생성 v1/초안의 두 단계 설명은 기존 경로이며 납입 전용 writer와 구분한다. v4→v5는 기존 열·행·원문/hash를 보존하고 납입 테이블 6개만 추가한다.

`LedgerRepository`의 로컬 구현이다. `app → core:data → core:model` 의존 방향을 유지하며 Room Entity와 DAO는 모듈 내부에 둔다. `di`의 `LedgerDatabaseModule`·`LedgerClockModule`은 파일 DB와 서울 Clock을 Singleton으로 제공하고, `LedgerRepositoryModule`은 생성자 주입 가능한 Room 구현을 Repository 계약에 Singleton으로 바인딩한다. 홈·거래·계좌 조회와 [입력·계좌 등록](../../feature/transactions/ENTRY.md)을 연결한다.

공통 실행 의존성은 [core:common](../common/README.md)에서 제공한다. Repository는 `CoroutineContextProvider`를 생성자로 주입받고 `default`로 요청 직렬화·해시·복원 준비를 실행한다. Room 트랜잭션 경계와 호출자 취소는 유지한다. Android Context는 DB 제공부에서 Hilt의 `@ApplicationContext`로 주입하며 Repository나 전역 객체에 보관하지 않는다.

## 지원 경로

1. `openLedger()`가 기존 단일 장부의 context를 반환한다. 유효 메타가 한 행이어도 제출 참조를 포함한 여섯 업무 테이블에 장부 메타가 없는 행이 있으면 `MISSING_METADATA`로 반환한다. 모든 업무 기록까지 비어 있는 최초 저장소에서만 같은 트랜잭션 안에서 UUID 쌍과 revision 1 메타를 생성한다. 메타 누락·손상·복수 장부는 `RecoveryRequired`이며 기존 행을 변경하지 않는다. 조회·쓰기 오류와 취소는 전파한다. 기존 `initialize(context)`도 유지한다. 자동 계좌·0원 기준·샘플 거래를 만들지 않는다.
2. 계좌 생성은 계좌와 미확인 기준 상태를 함께 저장한다. 기준일은 서울 오늘 이전, 1900-01-01 이후이며 미확인 금액·확인 시각은 null이다.
3. 수입·지출·내부 이체를 생성한다. 실제일은 서울 오늘까지, 금액은 1..999,999,999,999원이다. 참조 계좌는 같은 가계부의 활성 계좌여야 한다. 이체는 두 계좌를 참조하는 한 행이다.
4. `snapshot(context, asOf)`는 같은 DB 트랜잭션에서 목록·잔액·선택 월 수입/지출을 계산한다. 기준일 당일 거래는 잔액에 재가산하지 않는다. 미확인 기준의 잔액은 null, 내부 이체는 수입/지출 합계에서 제외한다. 넓은 정수로 합산한 최종 결과의 범위를 검사한다.

모든 ID는 소문자 정규 UUID다. 이름·메모 등 업무 문자열은 유효한 Unicode와 UTF-8 16 KiB 상한을 검사한다. 이름이 같은 계좌도 별도 ID로 구분한다.

## 제출과 복구

저장 의도마다 호출자가 `operationId`를 한 번 생성하고, 응답 유실이나 실패 후 재시도에는 같은 ID와 값을 유지한다. 다른 내용을 제출할 때 기존 ID를 재사용하지 않는다.

- 첫 트랜잭션에서 `submissions` 요청과 `submission_references`의 원래 context·operationId를 함께 확정한다. 다음 단일 트랜잭션에서 금융 행·ledger revision·`operations` 성공 결과·제출 의도 소비를 확정하며 참조는 유지한다. 금융 변경 실패·취소 시 제출 의도와 참조가 남는다. 참조만 있고 원문 대응 증거가 없는 ID에 새 pending을 삽입해 복구를 가장하지 않는다.
- `(ledgerId, operationId)`의 같은 요청은 원래 receipt를 반환한다. 새 명령으로 간주하거나 revision을 다시 올리지 않는다. 다른 요청은 `DUPLICATE_REQUEST` 충돌이다.
- `receipt`로 성공 결과를 조회하고 `pendingWrites`·`retryPending`으로 캡처한 미확정 요청을 복구한다. 제출 의도는 성공 결과도, 입력 폼의 전체 초안도 아니다. API 예외만으로 성공 여부를 추정하지 않는다.
- UI 소비자는 `pendingRecovery(context)`로 검증된 계좌 등록·거래 생성 값과 행의 `operationId`를 조회한다. `TypedSubmission`은 `AccountRegistration(CreateAccount)`·`TransactionCreation(CreateTransaction)`만 수용하며 손상/미지원 항목은 포함하지 않는다. context 확인과 제출 조회는 같은 트랜잭션이며 쓰기·소비·실행 예약은 하지 않는다. 원문/hash는 공개 결과에 노출하지 않는다. 알 수 없는 version/kind는 미지원 항목, 잘못된 UUID/hash/context와 지원 형식의 비정규 원문은 손상 항목으로 남긴다. ID 자체가 잘못되면 재시도 키는 null이다. 정상 조회에 항목이 없을 때만 빈 목록을 반환한다. 조회 후 소비·세대 변경은 기존 `receipt`·`retryPending`에서 다시 확인해야 한다.
- 호출 context가 없거나 세대가 다르면 기존 `LedgerConflictException`으로 조회 전체를 거부한다. 항목의 `CONTEXT_MISMATCH`는 외부 행의 장부 귀속 또는 지원 형식의 내부 context가 호출 context와 다른 경우다. 행 ID → hash → 외부 ledgerId → version → 지원 v1의 내부 context → kind → 지원 payload 순으로 검사한다. 미지원 버전도 행 ID·hash·외부 귀속은 검증하되 내부 필드를 v1 형식으로 해석하지 않는다. 검증된 항목도 실행 가능성을 보장하지 않으므로 선택 후 `receipt`로 결과를 확인하고, 결과가 없을 때 사용자가 재시도를 선택하면 같은 ID로 `retryPending`한다. 해당 API들이 최신 상태를 다시 확인한다.
- context의 세대가 다르면 거부한다. ledger revision의 조건부 증가가 0행이면 금융 변경 전체를 롤백한다.

`submissionRecovery(context)`는 활성 참조와 참조 없는 기존 pending을 한 트랜잭션에서 대조한다. `Committed`는 검증된 원래 영수증, `Pending`은 활성 참조가 있는 검증된 계좌 등록·거래 생성 요청, `LegacyPending`은 참조 없이 남은 이전 typed 요청이다. 거래 typed 지원은 기존 `CREATE_TRANSACTION` v1의 수입·지출·이체에 한정하며 원래 거래 ID·실제일·금액·양쪽 계좌 참조·메모를 유지한다. 손상·세대 불일치·원장 부재·pending/성공 동시 존재는 `Blocked`로 보존한다. 식별 자체가 확인되지 않으면 실행 가능한 참조를 제공하지 않는다. 과거 성공 전체를 발견 목록으로 만들거나 읽기 중 등록·재시도하지 않는다. I/O·취소와 호출 context 충돌은 예외로 전파한다.

선택 재시도는 기존 `retryPending(context, operationId)`를 사용한다. 참조 없는 이전 계좌·거래 pending도 이 경로에서 첫 트랜잭션의 요청 확인·참조 등록을 거친 뒤 금융 트랜잭션으로 진행한다. 같은 요청 여부는 같은 context·ID에서 캡처한 canonical 원문과 hash로 판정한다. typed는 원래 요청의 유효성이며 현재 실행 가능성이나 성공을 보장하지 않는다. 현재 참조 계좌가 없거나 다른 장부 소유이면 writer가 `MISSING_REFERENCE`, 보관 계좌이면 `ARCHIVED_ACCOUNT`로 금융 쓰기를 거부한다. 이런 mutable 상태를 읽기에서 과거 캡처 손상으로 바꾸지 않는다. legacy의 명시 재시도는 금융 실패 시에도 첫 단계에서 등록한 참조·pending을 보존한다. 결과 확인·재시도 모두 원래 context·operationId를 사용하고 새 ID나 추정값을 만들지 않는다. 호출 계약은 [LedgerRepository](../model/src/main/kotlin/com/monit/core/model/LedgerModels.kt), 발견·종료 결과는 [SubmissionRecovery](../model/src/main/kotlin/com/monit/core/model/SubmissionRecovery.kt), typed 제출은 [LedgerRecovery](../model/src/main/kotlin/com/monit/core/model/LedgerRecovery.kt)를 참조한다.

`finishSubmissionRecovery(context, operationId)`는 사용자의 명시적인 결과 확인 후에만 호출한다. 같은 쓰기 트랜잭션에서 원래 세대·canonical 요청/hash·성공 kind·요청 대상과 영수증 참조·pending 부재를 검증하고 선택한 참조 하나만 삭제한다. 발견·종료·직접 영수증 조회·성공 재시도는 같은 트랜잭션에서 참조 대상 금융 행의 존재도 확인한다. 대상 계좌·기준 상태·거래 행이 없으면 손상으로 차단하고 활성 참조를 보존하며 현재 객체 revision을 과거 receipt 값으로 맞추지 않는다. `Finished` 뒤 응답이 유실돼도 재요청은 보존된 성공을 검증해 `AlreadyInactive`를 반환한다. 이는 과거 UI 확인 여부를 증명하지 않는다. 미확정/문제 항목은 `NotReady`이며 금융 행·revision·영수증·원문/hash·pending은 삭제하지 않는다. Back·화면 재수집·UI 효과 소비로 호출하지 않으며 자동 만료·일괄 삭제·미확정 제출 폐기는 제공하지 않는다. 종료 후 같은 금융 요청을 재전달해도 기존 영수증을 반환하고 참조를 부활시키지 않는다.

내부 요청 v1은 `version`, `ledgerId`, `expectedStorageGeneration`, `kind`, `targetId`, `expectedRevision`, `value`를 고정 순서로 캡처한다. 신규 명령의 `expectedRevision`은 null이며 종류는 `UPDATE_ACCOUNT` 또는 `CREATE_TRANSACTION`이다. 금액은 정수 문자열로 쓰고, 정확한 요청 UTF-8 bytes의 SHA-256을 보존한다. 이 형식은 서버 DTO·백업 포맷이 아니다. 성공 결과의 ledger revision과 참조 객체의 revision은 서로 구분한다.

`operationId`는 submissions 행에서 가져오며 v1 원문에 추가하지 않는다. 입력별 UTF-8 16 KiB 한도와 이스케이프를 포함한 캡처 원문 크기는 별개다. 복구 조회에 직렬화 원문 16 KiB 제한이나 단일 요청 8 MiB 제한을 추가하지 않는다. 전체 백업의 크기 검증은 후속 구현 범위이며 이 조회와 별개다.

## 스키마와 검증 경계

schema v3는 기존 `ledgers`, `accounts`, `anchors`, `transactions`, `operations`, `submissions`, `submission_references`와 미제출 초안 `form_drafts`를 사용한다. 명시적 v1→v2 Migration은 빈 참조 테이블만, v2→v3는 초안 테이블과 인덱스만 추가하며 기존 행·원문/hash를 보존한다. Room 라이브러리 버전 변경이 아니다. 과거 성공에 참조를 역생성하지 않으며 기존 pending은 계속 발견되고 선택 재시도 시 참조가 등록된다. 참조의 복합 키는 `(ledgerId, operationId)`이며 원래 `storageGeneration`을 유지한다. ledger FK만 두고 아직 없는 성공 원장이나 소비되는 pending을 FK 대상으로 삼지 않는다. ABORT 삽입을 사용하며 REPLACE·연쇄 삭제·destructive migration을 사용하지 않는다. 계좌당 현재 기준 상태 하나를 유지한다.

`DraftRepository`는 별도 계약이며 같은 singleton DB와 기존 금융 writer를 사용한다. 초안 원문은 장부/저장 세대·폼 종류·출처·부모 폼/선택 요청/슬롯으로 구분하고 revision CAS로 갱신한다. 최초 금융 캡처는 초안 잠금과 pending/참조를, 금융 commit은 금융 행·영수증·pending 소비·초안 소비를 각각 한 트랜잭션으로 처리한다. `retryPending`도 연결된 잠금 초안을 함께 소비한다. 초안은 금융 합계나 ledger revision에 포함하지 않는다. 명시 폐기/소비 뒤에는 payload를 지우고 ID/revision fence를 남겨 늦은 autosave를 거부한다. 부모 폐기는 자식·금융 제출을 연쇄 삭제하지 않는다. 자동 만료나 미확정 제출 폐기/수정은 제공하지 않는다. UI·저장·시험 경계는 [초안 복구 계획](../../docs/architecture/unsubmitted-draft-plan.md)을 따른다.

파일 DB는 WAL과 `synchronous=FULL`을 설정한다. Repository의 각 쓰기 트랜잭션에서 현재 writer 설정을 확인하고 불일치하면 거부한다. 이 설정 확인과 호스트 재열기 시험이 실제 기기의 강제 종료·재부팅·전원 손실 시험을 대신하지 않는다.

```powershell
.\gradlew.bat :core:model:test :core:data:testDebugUnitTest :app:assembleDebug :app:lintDebug :core:data:lintDebug
```

Robolectric 테스트는 실제 Room 생성 DAO와 파일 SQLite로 중복·참조 격리·실패 롤백·재열기 복구·동시 요청을 확인한다. 취소 시험은 Room 트랜잭션의 삽입 후 commit 전 롤백을 검사한다. 확인된 기준액·보관 상태·세대 전환은 일부 시험의 SQL fixture이며 제품 변경 API의 구현을 뜻하지 않는다.

`LedgerRecoveryTest`는 최초 진입 경합·재열기·메타 모순·각 업무 테이블의 고아 행·삽입 실패·대기 중 취소와 typed 조회의 원문 보존·손상/미지원 분류·오류 전파를 검사한다. 화면 연결·복원 UI·실기기 시험은 포함하지 않는다.

`SubmissionRecoveryTest`는 보존된 v1 schema로 생성한 실제 파일의 v2 이관·재열기, 등록/종료의 오류·Job 취소 rollback, 금융 commit 뒤 응답 미보관과 참조 재발견, 복수 참조의 선택 종료와 재요청, 세대·손상·미지원·원장 부재/중복·I/O 분리 및 조회 전후 행 보존을 검사한다. `TransactionPendingRecoveryTest`는 수입·지출·이체의 legacy/활성 typed 투영, 원문/ID 보존, 읽기 무변경, 명시 재시도/중복/응답 유실과 현재 계좌 상태의 writer 거부를 검사한다. 앱 삭제·기기 이전·백업 왕복 보존은 검증 범위가 아니다.

제품 폼/UI는 이 모듈의 변경 범위가 아니다. [거래 typed 복구 #43](https://github.com/1971123-seongmin/MONIT/issues/43)의 구현 PR에서 위 실제 파일 시험·기존 계좌/제출 복구 회귀·최신 CI와 필요한 리뷰 대응을 확인하고 병합하면 [입력·등록 연결 #40](https://github.com/1971123-seongmin/MONIT/issues/40)을 재개한다. 이후 feature에서 원래 context·ID의 결과 확인·명시 재시도·선택한 제출 참조의 명시 종료를 화면에 연결한다.

기준액 확인/변경, 이관·백업·복원, 서버 동기화와 Flow 기반 UI 조회는 후속 범위다. 소비자는 Repository 계약을 사용하고 DAO나 DB 파일을 직접 조작하지 않는다. 현재 전체 가계부를 읽는 계산은 초기 구현이며 데이터 규모에 따른 성능 검증은 별도로 필요하다.
