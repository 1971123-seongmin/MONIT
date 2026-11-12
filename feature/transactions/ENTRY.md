# 신규 거래 입력과 계좌 등록 연결

홈의 거래 기록, 거래 화면의 선택 날짜 기록, 계좌 화면의 계좌 추가에서 실제 입력 화면으로 진입한다. 각 NavBackStackEntry의 Hilt ViewModel이 입력을 소유하며 Google 권장 MVVM과 기본 viewModelScope를 사용한다. 화면은 공통 디자인 시스템을 조합한다.

## 데이터와 저장

- 앱이 검증한 LedgerContext를 최초 한 번 연결한다. 화면 진입은 submissionRecovery/snapshot 조회이며 장부 메타데이터를 생성하거나 수리하지 않는다. 복원된 다른 장부·세대를 현재 context로 덮어쓰지 않는다.
- 거래는 수입·지출·이체를 지원한다. 금액은 정수 원화 1~999,999,999,999원, 거래일은 엄격한 ISO 날짜로 서울 1900-01-01부터 오늘까지다. 첫 오류로 초점을 옮기고 입력 원문을 보존한다.
- 활성 계좌만 선택하며 이체의 두 계좌는 달라야 한다. 저장 직전 UI 검증과 Repository writer의 최종 참조·상태 검사를 함께 사용한다.
- 첫 유효 제출에서 operationId와 객체 ID를 고정한다. 연타·처리 중 편집을 막고 응답 유실 시 기존 영수증부터 확인한다. 결과 조회 실패는 결과 없음으로 취급하지 않는다. 결과 없음 확인 후 명시 재시도에서도 원래 ID와 내용을 사용한다.
- 제출 전 검증 오류는 편집할 수 있다. 제출을 캡처한 뒤에는 결과 확인/같은 내용 재시도만 제공하며 기존 제출을 수정·폐기하거나 새 ID로 바꾸는 기능은 없다. 현재 조건에서 실행할 수 없는 pending도 보존한다.
- 계좌 등록은 일반 계좌와 미확인 기준 잔액을 제공한다. 미확인은 0원으로 계산하지 않는다. 계좌 종류 확장·기준액 확인은 아직 제공하지 않는다.

## 계좌 선택과 복귀

거래 입력의 원래 entryId·선택 슬롯·selectionRequestId를 보존하고 Nav2의 작은 반환 Bundle을 받는다. 현재 요청과 맞는 결과만 현재 장부의 활성 계좌로 다시 조회한다. 등록 성공은 선택 후보이며 사용자가 ‘이 계좌 사용’을 눌러야 적용한다. 이체 반대편 계좌·금액·날짜·메모를 보존한다. 닫기는 후보를 적용하지 않는다.

Back/나가기는 확인 대화상자를 거치며 금융 저장 취소나 삭제가 아니다. 저장 완료 화면에서 사용자가 누르는 ‘등록 확인’ 또는 ‘목록 보기’만 검증된 해당 제출의 복구 참조 종료를 요청한다. 종료 실패 시 화면을 유지하고 금융 저장을 반복하지 않는다. 정확한 caller가 아니거나 아직 RESUMED가 아니면 Navigator는 이동하지 않으며 현재 화면에서 다시 시도할 수 있다. 원래 거래 화면으로 돌아가면 월·선택일·필터를 보존하고 Repository를 재조회한다.

## 재진입과 이전 제출 확인

새 입력 진입 전 앱의 EntryRecoveryGate가 기존 submissionRecovery를 읽는다. 활성 참조가 없다는 정상 결과일 때만 신규 입력을 연다. 복수 제출은 한 건씩 선택하고 receipt로 결과를 확인한다. Pending/LegacyPending의 재시도는 조회 성공·결과 없음 뒤 사용자의 명시 행동에서만 기존 retryPending을 호출한다. Committed는 확인된 결과를 보여 주고 명시 확인에서 그 참조 하나만 종료한다. Blocked·조회 실패는 신규 저장으로 우회하지 않으며 원본을 보존한다. Back·재구성·화면 결과 소비에서 일괄 재실행하거나 참조를 정리하지 않는다.

거래 입력 안에서 계좌 추가로 진입해도 등록 entry의 별도 gate를 거친다. 제출 전 거래 입력은 원래 entry ViewModel에 유지되며 등록을 위한 새 금융 context를 만들지 않는다. LedgerContext의 장부 ID/저장 세대, 복구 상태별 의미와 Repository 계약은 [로컬 저장 기반](../../core/data/README.md#제출과-복구)을 따른다. 반환 Bundle의 필드/검증은 [RegisteredAccountResult](src/main/kotlin/com/monit/feature/transactions/navigation/RegisteredAccountResult.kt), 소비 경계는 [RegisteredAccountResultEffect](src/main/kotlin/com/monit/feature/transactions/navigation/RegisteredAccountResultEffect.kt)를 참조한다.

현재 폼의 SavedStateHandle은 지원되는 task 재생성에서 작은 입력·호출 문맥·제출 캡처를 보존한다. 최근 앱 제거·강제 종료·재부팅 이후의 **미제출 입력폼 전체 영속 초안은 지원하지 않는다.** 제출 후 Repository에 남은 원문/참조 복구와 입력폼 초안 복구는 별개다. 초안 복구 AC는 미충족으로 유지한다.

## 검증과 남은 범위

고정 Clock·TestDispatcher로 입력/날짜 경계·연타·응답 유실·동일 제출 재시도·다른 세대 차단·반환 후보 명시 적용·복수 제출 확인을 검사한다. 앱 시험은 실제 Route·ViewModel·Nav2와 합성 Repository를 사용하고 Room 원자성 시험과 구분한다. 실제 Compose의 Native Canvas 렌더와 Preview를 제공하며 기기 IME·재부팅·성능 검증을 대신하지 않는다.

자동 시험은 [입력 VM 시험](src/test/kotlin/com/monit/feature/transactions/entry/TransactionEntryViewModelTest.kt), [앱 입력/복구/UI·렌더 시험](../../app/src/test/kotlin/com/monit/app/entry), [계좌 VM 시험](../accounts/src/test/kotlin/com/monit/feature/accounts/AccountRegistrationViewModelTest.kt)에 있다. 실제 실행 결과와 최신 커밋·CI는 연결 PR에서 확인한다. 미제출 영속 초안 AC가 남으므로 이 단위의 신규 저장 동선 통과를 초안 복구나 전체 앱 완료로 확대하지 않는다.

거래 수정·삭제, 기준액 확인, 저축/투자 상세, 납입 연결, 알림, 이관·백업, Supabase 연동과 미제출 영속 초안은 이 입력 단위의 구현 완료에 포함하지 않는다. 요구사항 자체를 삭제하거나 전체 앱 완료로 처리하지 않는다. 실제 기기의 기존 앱·DB는 보존하며 쓰기·복구 검증은 격리된 합성 시험 환경이 마련된 뒤 QA가 수행한다.
