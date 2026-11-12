# 계좌 등록 상태 계층

계좌 신규 등록의 상태·이벤트·표시 모델을 제공한다. `app → feature:accounts → core:model`이며 Room 구현은 기존 `app → core:data`의 Hilt binding으로 연결한다. ViewModel은 Room Entity·DAO·내부 requestJson을 알지 못한다.

ViewModel은 기본 `viewModelScope.launch`를 사용한다. 테스트는 Main을 TestDispatcher로 교체하고 종료 시 복원한다. Repository의 실행 환경은 [core:common의 CoroutineContextProvider](../../core/common/README.md)로 주입하며 계좌 저장은 호출자의 취소 관계를 유지한다. 장부 ID/세대인 `LedgerContext`는 기존 호출 계약을 유지한다.

## 호출 조건과 상태

앱의 검증된 장부 진입 결과를 `AccountRegistrationRoute → bind(context)`로 최초 한 번 연결한다. `SavedStateHandle`의 기존 `ledgerId`, `storageGeneration` 또는 제출 캡처를 현재 세대로 덮어쓰지 않는다. 식별자가 없으면 연결 전까지 `UNAVAILABLE / CONTEXT_REQUIRED`이며 잘못된 복원 정보는 차단한다. 진입은 읽기 전용 `submissionRecovery` 조회이며 장부를 생성하거나 수리하지 않는다.

context 생성·초기화 계약은 [Repository 사용 순서](../../core/data/README.md), 등록 작업 상태는 [AccountRegistrationState](src/main/kotlin/com/monit/feature/accounts/AccountRegistrationState.kt), 이벤트는 [Action](src/main/kotlin/com/monit/feature/accounts/AccountRegistrationAction.kt)을 참조한다. 실제 화면·계좌 선택 반환·이전 제출 복구 연결은 [입력 계약](../transactions/ENTRY.md)을 따른다.

입력은 `AccountRegistrationAction`을 Main에서 전달하고 읽기 전용 `state: StateFlow<AccountRegistrationState>`를 수집한다. 표시 데이터는 `state.value.uiState`로 얻는다. [AccountRegistrationUiState.kt](src/main/kotlin/com/monit/feature/accounts/AccountRegistrationUiState.kt)에는 순수 표시 데이터인 `AccountRegistrationUiState`와 그 자식 `AccountDraftUiState`·`AccountUiState`만 둔다. enum·Action·저장/조회 상태·제출/복원·mapper는 각 별도 파일에 있다. 명명·파일 기준은 [Android 지침](../../docs/ai/roles/android.md)을 따른다.

`AccountSaveState`는 초기화·편집·저장·결과 확인·미확정·성공·사용 불가를 구분하며 저장/확인 상태가 제출을, 성공 상태가 operationId와 accountId를 필수로 보유한다. `AccountReadState`는 조회 전·조회 중·조회 성공 모델·실패를 구분한다. `phase`·`problem`·`savedAccountId`·`account`·`readPhase`와 표시용 `uiState`는 등록 상태에서 계산하며 별도로 갱신하지 않는다. 초기화 실패는 `RetryInitialization`으로만 다시 초기화한다.

- 이름·미확인 사유는 기존 모델의 Unicode·UTF-8 검증을 사용한다. 기준일은 엄격한 ISO 날짜이며 서울 1900-01-01부터 어제까지다. 입력 오류 시 원문을 유지한다.
- 유효한 첫 제출에서 operation·계좌·기준점 ID를 한 번 만들고 요청을 고정한다. 저장·결과 확인·미확정 재시도 중에는 편집과 중복 제출을 받지 않는다.
- 응답 유실은 기존 제출의 영수증부터 확인한다. `CheckResult`는 영수증만 조회하고 결과가 없어도 쓰기를 하지 않는다. `RetrySave`는 결과를 다시 조회한 뒤 없을 때만 같은 ID·같은 typed 요청을 재전달한다. 조회 실패 시 쓰지 않으며 다른 종류의 pending 요청을 임의 실행하지 않는다.
- 영수증의 제출 ID·계좌/기준점 참조를 확인한 뒤에만 `SAVED`가 된다. 별도 `readPhase`로 실제 snapshot 조회 중·성공·실패를 표시하며 조회 실패가 확정된 저장을 취소하지 않는다. 조회 재시도는 저장을 다시 실행하지 않는다.
- 조회된 계좌의 잔액 null과 미확인 사유를 유지한다. 거래 합계나 기본 0원으로 대체하지 않는다.
- 성공 후 `StartNew`를 명시하면 새 제출이 가능하다. 같은 이름도 별도 계좌로 등록된다. 이전 요청의 늦은 조회는 새 입력 상태를 덮어쓰지 않는다.

## 복구의 실제 범위

하나의 saved-state 값에 제출 당시의 context·ID·typed 입력 스냅샷을 저장한다. `model/AccountSubmissionCodec`은 명명된 Bundle v2 필드로 기록하며 기존 ArrayList v1 task는 ID/내용을 보존해 읽은 뒤 v2로 기록한다. 알 수 없는 버전·필드/타입 오류·다른 context는 명시적으로 거부한다. 지원되는 재생성에서는 기존 결과부터 조회하며, 성공한 제출은 같은 계좌로 복귀하고 미확정 제출은 명시 Action을 기다린다. 취소 예외는 전파하고 성공을 추정하지 않는다.

`SavedStateHandle`은 task stack에 종속되는 보조 상태다. 미제출 원문은 별도 DraftRepository/Room의 마지막 commit에서 명시 복구한다. 계좌 이름/유형/기준일/미확인 사유는 편집 원문으로 보존하고, 독립 등록과 거래 부모 초안/선택 요청/슬롯을 구분한다. 500ms 저장·focus/이탈/제출 flush·마지막 저장본/실패 안내는 [입력 계약](../transactions/ENTRY.md#재진입과-이전-제출-확인)을 따른다. 이미 제출한 pending의 폐기·수정은 지원하지 않는다. Repository의 영속 제출과 화면의 saved-state 스냅샷을 동일한 보장으로 취급하지 않는다. [Android saved-state 문서](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate)를 따른다.

호스트 Activity의 saved state만으로 최신 입력 보존을 보장하지 않는다. 새 입력 전에 앱의 영속 제출 발견 경계가 기존 참조를 조회하며 자동으로 새 ID를 제출하지 않는다. 초안 flush 뒤 원래 reference/revision/호출 문맥을 제출 캡처와 함께 유지하고, 재시도도 같은 제출에 연결한다. 아직 Room commit이 확인되지 않은 최신 입력과 앱 삭제/DB 손상은 복구 보장 밖이며 S23 수용시험 결과는 자동 시험과 별도로 확인한다.

예상하지 못한 예외는 작업 종류와 예외 클래스명만 Android 로그에 남긴다. 예외 메시지·Throwable·계좌명·금액·ID는 기록하지 않는다. 이 진단 정보가 성공 판정이나 재시도 정책을 바꾸지는 않는다.

요청 크기 P2의 Backend 계약 보완은 완료됐다. 입력별 UTF-8 16 KiB와 직렬화 원문은 서로 다른 검증 경계다. 현재 `captureWrite → submit`은 캡처 JSON에 입력용 16 KiB 검사를 재사용하지 않는다. 이름·사유가 각각 정확히 16 KiB이고 캡처 JSON은 이를 초과하는 실제 Room 시험에서 실패 롤백·재열기·같은 제출 재시도와 원문/hash 보존을 확인했다. 큰 이스케이프 입력이 이 경로에서 크기 때문에 거부된다는 기존 설명은 근거가 없어 정정한다.

전체 백업 JSON의 UTF-8 8 MiB 제한은 백업 writer/reader의 책임이며 개별 Room 요청의 새 상한이 아니다. 백업 round-trip은 아직 구현·검증하지 않았다. Backend 작업본 `docs/architecture/local-ledger-contract.md` 8.1절과 `docs/contracts/README.md` P2는 조회했으나 현재 main에는 등록되지 않아 상대 링크를 완료된 원본처럼 연결하지 않는다. 후속 준비와 검증 경계는 [개발 계획](../../docs/architecture/monit-modules-di-ui-plan.md#11-열린-결정반대-의견중단-조건)을 따른다.

## 검증과 다음 단위

`AccountRegistrationViewModelTest`는 합성 Repository·고정 Clock·TestDispatcher·gate로 기존 상태 시나리오와 읽기 전용 확인·v1 복원·손상 복원·늦은 조회 실패/취소를 검증한다. `AccountRegistrationModelsTest`는 mapper·validator·영수증 참조 검증과 Robolectric의 Bundle/Parcel 왕복을 검사한다. Android Activity saved-instance-state 전체 수명주기나 실제 기기의 강제 종료·재부팅을 시험한 것은 아니다. 실제 Room의 원자성과 중복 저장은 기존 `core:data` 통합 테스트가 검증한다.

계좌 등록 Route/Screen·top-level Preview와 거래 입력 왕복을 연결했다. 합성 호스트 렌더와 실제 Route/VM/Nav2 UI 시험을 제공한다. Prevue·실기기 실행 및 IME·재부팅 검증은 해당 실행 증거가 있을 때만 보고하며 실기기 조작은 QA가 담당한다.

기준액 확인·수정·삭제·서버 동기화는 지원 API가 준비되기 전까지 후속 범위다. PR은 계좌 상태 계층의 완료이며 첫 제품 동선 전체나 PRD 전체의 완료가 아니다.
