# 계좌 등록 상태 계층

계좌 신규 등록의 상태·이벤트·표시 모델을 제공한다. `app → feature:accounts → core:model`이며 Room 구현은 기존 `app → core:data`의 Hilt binding으로 연결한다. ViewModel은 Room Entity·DAO·내부 requestJson을 알지 못한다.

## 호출 조건과 상태

호출자는 `SavedStateHandle`의 `ledgerId`, `storageGeneration`에 기존 장부의 정규 UUID를 전달해야 한다. 식별자가 없거나 잘못되면 `UNAVAILABLE / CONTEXT_REQUIRED`이며 새 장부를 자동 생성하지 않는다. 현재 앱의 진입 화면은 placeholder이며, 안정적인 장부 식별자 소유·재진입 경로와 실제 화면 연결은 후속 작업이다. Hilt 앱 빌드는 정적 의존성 연결의 검증이며 실제 화면에서의 주입·기기 실행 검증을 뜻하지 않는다.

입력은 `AccountRegistrationAction`을 Main에서 전달하고 읽기 전용 `uiState`를 수집한다. `INITIALIZING → EDITING → SAVING → SAVED`를 기본으로, 결과가 불명확하면 `CHECKING_RESULT → RETRY_AVAILABLE`로 구분한다. 초기화 실패는 별도 원인을 표시하고 명시적으로 다시 초기화한다. 충돌·손상 복원·다른 제출 영수증은 실행 불가 상태로 남긴다.

- 이름·미확인 사유는 기존 모델의 Unicode·UTF-8 검증을 사용한다. 기준일은 엄격한 ISO 날짜이며 서울 1900-01-01부터 어제까지다. 입력 오류 시 원문을 유지한다.
- 유효한 첫 제출에서 operation·계좌·기준점 ID를 한 번 만들고 요청을 고정한다. 저장·결과 확인·미확정 재시도 중에는 편집과 중복 제출을 받지 않는다.
- 응답 유실은 기존 제출의 영수증부터 확인한다. 영수증이 없는 경우 자동 쓰기를 하지 않으며, 명시 Retry에서 결과를 다시 조회한 뒤 같은 ID·같은 typed 요청만 재전달한다. 다른 종류의 pending 요청을 임의 실행하지 않는다.
- 영수증의 제출 ID·계좌/기준점 참조를 확인한 뒤에만 `SAVED`가 된다. 별도 `readPhase`로 실제 snapshot 조회 중·성공·실패를 표시하며 조회 실패가 확정된 저장을 취소하지 않는다. 조회 재시도는 저장을 다시 실행하지 않는다.
- 조회된 계좌의 잔액 null과 미확인 사유를 유지한다. 거래 합계나 기본 0원으로 대체하지 않는다.
- 성공 후 `StartNew`를 명시하면 새 제출이 가능하다. 같은 이름도 별도 계좌로 등록된다. 이전 요청의 늦은 조회는 새 입력 상태를 덮어쓰지 않는다.

## 복구의 실제 범위

하나의 saved-state 값에 제출 당시의 context·ID·typed 입력 스냅샷을 저장한다. 지원되는 saved-state 재생성에서는 기존 결과부터 조회하며, 성공한 제출은 같은 계좌로 복귀하고 미확정 제출은 명시 Retry를 기다린다. 취소 예외는 전파하고 성공을 추정하지 않는다.

`SavedStateHandle`은 task stack에 종속된다. 강제 종료·최근 앱 제거·재부팅 후에도 유지되는 영속 저장소가 아니다. 아직 제출하지 않은 입력폼 전체 초안, 장부 식별자 bootstrap, saved-state가 사라진 뒤의 Room pending 제출 선택·복구, 미확정 제출의 폐기·수정 UI는 이번 구현에 없다. Repository의 영속 제출과 화면의 saved-state 스냅샷을 동일한 보장으로 취급하지 않는다. [Android saved-state 문서](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate)를 따른다.

현재 업무 문자열 검증과 정규화 requestJson 크기의 차이(P2)는 Backend 확정 대기이며, 이 모듈에서 임의의 새 상한이나 내부 JSON 파서를 도입하지 않는다. 큰 이스케이프 문자열 등은 저장 단계에서 거부될 수 있다.

## 검증과 다음 단위

`AccountRegistrationViewModelTest`는 합성 Repository·고정 Clock·TestDispatcher·중단 가능한 gate로 중복 탭, 날짜/문자열 경계, 응답 유실, 동일 제출 재시도, 조회 실패, 취소·재생성, 세대 격리, 늦은 조회, 별개 동일 이름 등록을 검증한다. JVM에서 스냅샷을 새 handle로 전달하는 시험이며 Android saved-instance-state 왕복·강제 종료·재부팅을 시험한 것은 아니다. 실제 Room의 원자성과 중복 저장은 기존 `core:data` 통합 테스트가 검증한다.

다음은 거래 입력·월간 조회의 상태 계층, 명세에 맞는 Compose 화면·탐색·접근성 연결이다. 화면에는 합성 UiState·콜백을 받는 Content 경계와 발견 가능한 top-level Preview를 제공한다. Prevue 도구 설치는 QA의 Windows/WSL 검증 결과를 재사용한다. 이 모듈에는 아직 제품 화면/Preview가 없어 렌더 성공을 주장하지 않는다. 실기기 조작은 QA가 담당한다.

기준액 확인·수정·삭제·서버 동기화는 지원 API가 준비되기 전까지 후속 범위다. PR은 계좌 상태 계층의 완료이며 첫 제품 동선 전체나 PRD 전체의 완료가 아니다.
