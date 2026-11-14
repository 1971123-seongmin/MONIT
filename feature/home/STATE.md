# 월별 납입 등록·수동 확인·정정

Issue #60의 구현 경계다. 거래 수정·삭제는 [기존 상태 문서](../transactions/STATE.md)를 유지하고, 그 writer 안에서 연결된 납입의 금융 근거만 함께 갱신한다. 이 문서는 납입 계약을 다룬다. 별도 구현된 [알림](REMINDERS.md)·[이관 writer](../../docs/architecture/import-writer-plan.md)와 책임을 구분하며 실제 사용자 자료 반영·원격 동기화는 보류다.

## 화면과 책임

홈의 이번 달 요약 → 월별 납입 → 회차 상세 → 새 납입 입력 또는 기존 거래 선택 → 내용 검토 → 최종 확인 → 결과 확인 순서다. 반복 규칙 등록·미래 변경·일정 종료와 연결 해제·건너뜀·해제도 최종 확인이 필요하다. 입력·선택·검토·Back 자체는 금융 기록이나 완료를 만들지 않는다.

`PaymentsViewModel`이 entry 범위 `viewModelScope`와 주입한 `CoroutineContextProvider`를 사용한다. 화면은 표시 값·콜백을 받고, 조회·숫자 포맷·기존 거래 후보 준비는 default dispatcher에서 수행한다. `PaymentsUiState.kt`에는 순수 표시 데이터와 직접 자식만 두고 진행 상태·명령·enum·변환은 분리한다. 상태를 직접 갱신하는 MVVM이며 범용 reducer나 기계적 UseCase 계층은 추가하지 않는다.

납입 feature는 기존 `feature:home` 안에 둔다. feature가 목적지를 소유하고 app의 `MainNavigator`가 진입·복귀를 검증한다. 연결 거래 수정 뒤에는 같은 납입 caller로 돌아와 재조회한다. 실패한 조회를 0건·0원으로 표시하지 않는다. 긴 목록은 안정적인 key/contentType, 입력·확인 화면은 IME 대응 세로 스크롤을 사용한다.

## 월항목과 거래

- 규칙은 월 포함 범위와 버전을 가진다. 규칙별 회차는 `(ledgerId, ruleId, month)` 유일이며 31일은 해당 월 말일로 보정한다. 조회는 아직 저장하지 않은 회차를 revision 0의 가상 항목으로 투영하며 DB에 생성하지 않는다. 최초 명시 상태 변경에서만 같은 결정적 ID로 저장한다.
- 규칙 변경은 다음 달 이후 명시 적용 월부터다. 이미 저장된 미래 활성 PENDING만 ID를 유지해 갱신한다. 과거·현재·완료·건너뜀·재확인 snapshot은 보존한다. 이후 적용 월에 해당하는 버전 중 가장 나중에 확정한 revision이 우선한다.
- 종료 월은 현재 월 및 시작 월 이상이며 그 월을 포함한다. 이후 PENDING은 비활성화하고 완료·건너뜀·재확인은 예외 이력으로 표시한다. 종료를 늘려 자동 재활성화하지 않는다.
- 일반·보험은 실제 출금 계좌의 EXPENSE, 저축·투자는 GENERAL 출금 → 지정 SAVINGS/INVESTMENT 입금의 TRANSFER다. 계좌 활성·성격·날짜·금액은 최종 writer에서 다시 검증한다.
- 새 거래와 완료는 한 원자 확정이다. 기존 거래 연결은 원래 ID와 financialRevision을 확인하며 복제하지 않는다. 실제 계좌·날짜·금액은 이미 기록된 값으로 표시한다. 다른 회차에 예약된 거래와 부분 기록 거래는 전체 완료 후보에서 제외하고 writer도 검증한다.
- 전액 여부는 사용자 명시 선택이다. 예정액 차이·부분 기록에는 사유가 필요하다. 부분은 거래와 근거만 보존하고 회차는 PENDING, 활성 완료 연결은 없다. 분할 합산·잔여 자동 생성·이전 부분 거래의 전액 재사용은 하지 않는다.
- ACTIVE 연결은 거래와 회차 1:1이며 RECONFIRM도 예약을 유지한다. 연결 해제는 거래·잔액·실제 월 합계를 보존한다. 건너뜀은 완료가 아니다. 연결된 항목은 먼저 해제해야 건너뛸 수 있다.
- 연결 거래의 금융값 수정은 같은 기존 거래 트랜잭션에서 RECONFIRM으로 바꾼다. 메모만 수정하면 기존 완료를 유지한다. 삭제는 활성 연결을 해제하고 재확인을 표시하며 이전 확인 snapshot·이력을 보존한다. 회차의 대상 월과 금액의 실제 거래 월은 구분한다.

## 저장과 원래 요청 복구

Room schema 5는 규칙·버전·월항목·append-only 근거·납입 거절·원래 intent 테이블 6개만 추가한다. 기존 schema 4의 열·행과 생성 v1/수정·삭제 v2 canonical 원문·hash를 변경하지 않는다. 신규 납입 명령만 strict canonical v3를 사용한다. 파괴적 Migration·REPLACE·연쇄 삭제를 추가하지 않는다.

납입 명령은 먼저 별도 트랜잭션으로 원래 `operationId`·context/저장 세대·canonical/hash·draft 참조와 scope를 `payment_intents`에 보존한다. 이 단계는 전역 pending/ref나 초안 잠금을 만들지 않는다. 이후 **납입 전용 단일 outer Room 트랜잭션**에서 capture/ref/lock → 현재 검증 → 금융·규칙·회차·근거 → ledger revision·receipt → pending/초안 소비를 함께 확정한다. 기존 v1/v2의 두 단계 writer는 그대로 둔다.

입력·계좌 예약·I/O·취소·장부 CAS 실패는 outer 전체를 rollback한다. 사전 intent와 편집 가능한 초안은 남고 전역 Gate를 막는 납입 pending/ref/lock은 남지 않는다. 원래 의도는 성공 표시가 아니다. 원래 operationId와 다른 payload는 거부하며 재시도에 현재값·새 ID·새 revision을 대신 넣지 않는다.

원래 성공 receipt가 가장 먼저다. 금융 변경 전 확인한 명확한 규칙·월항목 revision 또는 선택한 기존 거래의 캡처된 financialRevision 불일치만 terminal 거절로 보존한다. 대상이 없거나, 세대·원문/hash가 틀리거나, 미지원/손상·I/O·취소·장부 CAS 실패인 경우를 terminal로 오분류하지 않는다. 거절은 값으로 반환해 금융 무변경·원문·결과·REJECTED 초안을 commit한 다음 트랜잭션 밖에서 전달한다. 사용자 결과 확인은 선택한 활성 참조 하나만 종료하며 원문·거절·근거를 삭제하거나 부활시키지 않는다.

응답 유실·취소 뒤 결과 불명확 상태에서는 같은 세대와 원래 ID의 receipt/거절/intent를 원자 조회한다. 조회 실패는 불명확 상태이며 새 편집·재제출을 열지 않는다. 확정 결과가 없음을 확인한 뒤에만 같은 요청 명시 재시도 또는 명시 해제를 허용한다. intent 저장 전 실패도 같은 ID로 재전달한다. 해제는 READY→RELEASED 또는 아직 없는 원래 intent의 RELEASED fence를 저장하므로 늦게 도착한 원래 writer가 commit할 수 없다. 원문 초안은 복구 후 사용자가 다시 검토·확정해야 새 요청을 만든다.

## 초안과 검증 경계

규칙·회차·기대 revision·출처를 scope에 고정하고 불완전한 숫자·날짜도 문자열 그대로 보관한다. 검토·최종 제출 전 최신 draft flush와 내용/scope 일치를 확인한다. Back은 보관·폐기를 묻고 새 entry는 명시 복구를 요구한다. 검토 중 재생성 후 복구한 초안도 다시 검토해야 한다. 자동 복구·금융 성공 추정·자동 제출을 하지 않는다.

`PaymentRepositoryTest`는 실제 파일 Room의 월말·회차/연결 유일성·부분/차액·미래 변경/종료·금융 수정/삭제·경합·SQL/Job 취소 rollback·동일 요청·응답 유실·해제 fence·strict v3를 검증한다. `PaymentMigrationTest`는 보존된 schema 4로 모든 기존 업무 테이블을 채운 파일을 schema 5로 이관·재열기하고 옛 원문·성공·pending·거절·초안과 새 쓰기를 확인한다.

VM 시험은 제어 가능한 프로토콜 대역으로 원문·명시 확인·복구·연타·실패/불명확 상태를, `PaymentsJourneyTest`는 실제 Route/VM/Nav2의 입력·확인·Back·caller 복귀와 Native Canvas Light/Dark/320dp·큰 글꼴을 확인한다. 대역이 실제 금융 원자성을 검증한다고 주장하지 않는다. Preview는 고정 합성 표시 값만 사용한다.

호스트 렌더/시험은 S23의 시스템 IME·실제 프로세스 강제 종료·전원 손실·release 성능 검증과 다르다. #61은 main에 병합됐으며 S23 지정 수용시험과 남은 성능·장애·알림 검증은 [현재 상태](../../docs/README.md#검증과-남은-일)를 따른다. 전체 출시 준비 완료로 표현하지 않는다.
