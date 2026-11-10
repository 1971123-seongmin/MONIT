# 계좌 등록 상태 계층

계좌 신규 등록의 상태·이벤트·표시 모델을 제공한다. `app → feature:accounts → core:model`이며 Room 구현은 기존 `app → core:data`의 Hilt binding으로 연결한다. ViewModel은 Room Entity·DAO·내부 requestJson을 알지 못한다.

ViewModel은 기본 `viewModelScope.launch`를 사용한다. 테스트는 Main을 TestDispatcher로 교체하고 종료 시 복원한다. Repository의 실행 환경은 [core:common의 CoroutineContextProvider](../../core/common/README.md)로 주입하며 계좌 저장은 호출자의 취소 관계를 유지한다. 장부 ID/세대인 `LedgerContext`는 기존 호출 계약을 유지한다.

## 호출 조건과 상태

호출자는 `SavedStateHandle`의 `ledgerId`, `storageGeneration`에 기존 장부의 정규 UUID를 전달해야 한다. 식별자가 없거나 잘못되면 `UNAVAILABLE / CONTEXT_REQUIRED`이며 새 장부를 자동 생성하지 않는다. 현재 앱의 진입 화면은 placeholder이며, 안정적인 장부 식별자 소유·재진입 경로와 실제 화면 연결은 후속 작업이다. Hilt 앱 빌드는 정적 의존성 연결의 검증이며 실제 화면에서의 주입·기기 실행 검증을 뜻하지 않는다.

context 생성·초기화 계약은 [Repository 사용 순서](../../core/data/README.md), 전체 입력·상태·Action 정의는 [상태 모델](src/main/kotlin/com/monit/feature/accounts/AccountRegistrationState.kt)을 참조한다. context를 준비하는 제품 진입점은 아직 없으므로 화면 연결 예시는 후속 구현에서 제공한다.

입력은 `AccountRegistrationAction`을 Main에서 전달하고 읽기 전용 `uiState`를 수집한다. `AccountSaveState`는 초기화·편집·저장·결과 확인·미확정·성공·사용 불가를 구분하며 저장/확인 상태가 제출을, 성공 상태가 operationId와 accountId를 필수로 보유한다. `AccountReadState`는 조회 전·조회 중·조회 성공 모델·실패를 구분한다. `phase`·`problem`·`savedAccountId`·`account`·`readPhase`는 이 상태에서 계산하는 읽기 전용 표시 값이며 별도로 갱신하지 않는다. 초기화 실패는 `RetryInitialization`으로만 다시 초기화한다.

- 이름·미확인 사유는 기존 모델의 Unicode·UTF-8 검증을 사용한다. 기준일은 엄격한 ISO 날짜이며 서울 1900-01-01부터 어제까지다. 입력 오류 시 원문을 유지한다.
- 유효한 첫 제출에서 operation·계좌·기준점 ID를 한 번 만들고 요청을 고정한다. 저장·결과 확인·미확정 재시도 중에는 편집과 중복 제출을 받지 않는다.
- 응답 유실은 기존 제출의 영수증부터 확인한다. `CheckResult`는 영수증만 조회하고 결과가 없어도 쓰기를 하지 않는다. `RetrySave`는 결과를 다시 조회한 뒤 없을 때만 같은 ID·같은 typed 요청을 재전달한다. 조회 실패 시 쓰지 않으며 다른 종류의 pending 요청을 임의 실행하지 않는다.
- 영수증의 제출 ID·계좌/기준점 참조를 확인한 뒤에만 `SAVED`가 된다. 별도 `readPhase`로 실제 snapshot 조회 중·성공·실패를 표시하며 조회 실패가 확정된 저장을 취소하지 않는다. 조회 재시도는 저장을 다시 실행하지 않는다.
- 조회된 계좌의 잔액 null과 미확인 사유를 유지한다. 거래 합계나 기본 0원으로 대체하지 않는다.
- 성공 후 `StartNew`를 명시하면 새 제출이 가능하다. 같은 이름도 별도 계좌로 등록된다. 이전 요청의 늦은 조회는 새 입력 상태를 덮어쓰지 않는다.

## 복구의 실제 범위

하나의 saved-state 값에 제출 당시의 context·ID·typed 입력 스냅샷을 저장한다. `model/AccountSubmissionCodec`은 명명된 Bundle v2 필드로 기록하며 기존 ArrayList v1 task는 ID/내용을 보존해 읽은 뒤 v2로 기록한다. 알 수 없는 버전·필드/타입 오류·다른 context는 명시적으로 거부한다. 지원되는 재생성에서는 기존 결과부터 조회하며, 성공한 제출은 같은 계좌로 복귀하고 미확정 제출은 명시 Action을 기다린다. 취소 예외는 전파하고 성공을 추정하지 않는다.

`SavedStateHandle`은 task stack에 종속된다. 강제 종료·최근 앱 제거·재부팅 후에도 유지되는 영속 저장소가 아니다. 아직 제출하지 않은 입력폼 전체 초안, 장부 식별자 bootstrap, saved-state가 사라진 뒤의 Room pending 제출 선택·복구, 미확정 제출의 폐기·수정 UI는 이번 구현에 없다. Repository의 영속 제출과 화면의 saved-state 스냅샷을 동일한 보장으로 취급하지 않는다. [Android saved-state 문서](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate)를 따른다.

호스트 Activity가 중지될 때 저장된 값만 복원된다. 중지된 동안 제출한 뒤 다시 시작·중지되기 전에 프로세스가 종료되면 task가 남아 있어도 최신 제출 스냅샷이 없을 수 있다. 이 경우 기존 제출의 결과 조회를 보장하지 못하며, 새 ID의 등록으로 중복될 위험이 있다. 실제 화면 연결 전 영속 장부 진입·pending 복구 계약에서 해결해야 한다.

예상하지 못한 예외는 작업 종류와 예외 클래스명만 Android 로그에 남긴다. 예외 메시지·Throwable·계좌명·금액·ID는 기록하지 않는다. 이 진단 정보가 성공 판정이나 재시도 정책을 바꾸지는 않는다.

현재 업무 문자열 검증과 정규화 requestJson 크기의 차이(P2)는 [개발 계획의 열린 결정](../../docs/architecture/monit-modules-di-ui-plan.md#11-열린-결정반대-의견중단-조건)의 Backend 확정 대기 항목이며, 이 모듈에서 임의의 새 상한이나 내부 JSON 파서를 도입하지 않는다. 큰 이스케이프 문자열 등은 저장 단계에서 거부될 수 있다.

## 검증과 다음 단위

`AccountRegistrationViewModelTest`는 합성 Repository·고정 Clock·TestDispatcher·gate로 기존 상태 시나리오와 읽기 전용 확인·v1 복원·손상 복원·늦은 조회 실패/취소를 검증한다. `AccountRegistrationModelsTest`는 mapper·validator·영수증 참조 검증과 Robolectric의 Bundle/Parcel 왕복을 검사한다. Android Activity saved-instance-state 전체 수명주기나 실제 기기의 강제 종료·재부팅을 시험한 것은 아니다. 실제 Room의 원자성과 중복 저장은 기존 `core:data` 통합 테스트가 검증한다.

다음은 거래 입력·월간 조회의 상태 계층, 명세에 맞는 Compose 화면·탐색·접근성 연결이다. 화면에는 합성 UiState·콜백을 받는 Content 경계와 발견 가능한 top-level Preview를 제공한다. Prevue 도구 설치는 QA의 Windows/WSL 검증 결과를 재사용한다. 이 모듈에는 아직 제품 화면/Preview가 없어 렌더 성공을 주장하지 않는다. 실기기 조작은 QA가 담당한다.

기준액 확인·수정·삭제·서버 동기화는 지원 API가 준비되기 전까지 후속 범위다. PR은 계좌 상태 계층의 완료이며 첫 제품 동선 전체나 PRD 전체의 완료가 아니다.
