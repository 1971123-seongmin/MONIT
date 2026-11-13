# 계좌 기준액과 자산 집계

계좌 목록에서 상세로 진입하고, 저장된 기준일 C의 마감액 B를 입력한 뒤 영향 검토와 최종 명시 확정으로 저장한다. 최초 입력은 비어 있으며 미확인과 확인한 0원·음수를 구분한다. 기준일 일반 변경, 현재 은행 잔액에서 과거 금액 역산, 자동 0원 확인은 제공하지 않는다. 미확인 기준액은 거래·수동 납입을 막지 않는다. 상세의 거래 기록 버튼은 기존 거래 탭으로 이동하며, 해당 계좌는 기존 계좌 필터에서 직접 선택한다. 미제출 기준액 입력 중에는 이 행동을 노출하지 않는다.

## 계산과 표시 범위

- 장부 잔액은 `B + Σ delta(C < 실제일 ≤ 조회일)`이다. C 당일 거래는 B에 이미 포함되어 다시 더하지 않는다. 조회일<C이면 미확인이다. 금액은 기존 L1/L2 범위와 정확한 합산 규칙을 따른다. 기준액 변경은 날짜별 마감액의 전체 경로와 활성 계좌 소계 범위도 검증한다.
- 현재 소계는 활성 KRW 계좌 중 확인된 잔액만 포함한다. 보관 계좌·미확인 계좌는 제외 이유와 수를 표시한다. 저축·투자 장부 소계는 일반 계좌도 제외한다. 미확인 소계를 0원으로 표시하지 않는다. 시장 평가액·수익률·순자산으로 표현하지 않는다.
- 선택 월의 활성 실제 거래와 거래 당시 성격으로 일반→저축/투자 이체는 납입, 역방향은 인출, 차이는 순납입이다. 저축/투자끼리·일반끼리 재배치와 보험·직접 수입/지출은 이 통계에서 제외한다. 일반 수입·지출 및 계좌 잔액 효과는 유지한다. C로 월 통계를 자르지 않고 예정액·납입 체크 수로 금액을 만들지 않는다.
- 일반 거래 수정의 구/신 관련 계좌에서 `실제일>C` 반영 여부가 달라지면 날짜·계좌 변경을 함께 차단한다. 최초 캡처 전과 실제 금융 반영 경계에서 검사한다. 이 경우 금융·revision·이력·납입 연결/상태는 바뀌지 않으며 기준 잔액 재확인이 필요함을 알린다. 성공한 과거 operation은 원래 receipt부터 확인한다. 별도 정정 흐름은 구현하지 않는다.

## 저장·복구 경계

`BalanceAnchor.revision`은 account revision과 별개다. `ConfirmAnchor`는 account/anchor ID, 검토한 anchor revision, 변경하지 않을 C, B와 사유를 담는다. B는 ±999,999,999,999원이며 사유는 원문·UTF-8 범위를 검증한다. 최초 확인의 사유는 선택이고, 이미 확인된 기준액 변경은 필수다. writer가 CAS를 통과한 실제 이전 상태로 다시 검증한다. 이력의 보존된 이전 금액 null 여부에서 `INITIAL_CONFIRMATION`/`CORRECTION` 유형을 결정하며 사용자 사유 문장을 만들어 저장하지 않는다.

생성 v1·거래 수정/삭제 v2의 **기존 두 단계 제출 경계**를 재사용한다. 납입 전용 v3 intent/outer/fence를 새 범용 엔진으로 확장하지 않는다. 새 명령은 strict canonical v4 `CONFIRM_ANCHOR`이며 기존 v1/v2/v3 원문·필드 순서·hash·operation/context는 재작성하지 않는다.

1. 원래 context/operation/요청 원문·hash·제출 참조와 정확한 초안 revision lock을 먼저 보존한다.
2. 다음 단일 Room 트랜잭션에서 anchor 자체 CAS, 이전 기준 상태·revision/새 금액/사유/확인 시각 이력, ledger revision, receipt, pending 및 초안 소비와 알림 dirty를 확정한다. 계좌 revision은 변경하지 않으며 거래·납입 완료를 생성하지 않는다.
3. 명백한 anchor revision 불일치만 `AnchorRejected`로 확정한다. 원문과 REJECTED 초안을 보존하고 명시 결과 확인만 참조를 종료한다. I/O·취소·세대·손상·입력·ledger CAS 실패를 확정 거절로 바꾸지 않는다. 두 번째 단계 실패는 금융/이력/결과를 rollback하며 첫 단계 캡처는 남아 같은 요청으로만 재시도한다.
4. 성공 receipt가 우선이며 새 revision으로 과거 영수증을 맞추지 않는다. 새 기준액 성공은 해당 operation의 검증된 이력과 현재 anchor의 존재/동일 소유·기준일/이후 revision도 대조한다. 앱 재진입은 기존 EntryRecoveryGate의 typed 조회·명시 재시도로 연결된다. 서로 다른 명령의 동일 operation 충돌은 원문/납입 intent fence와 대조해 차단한다.

schema7은 `anchor_history`, `anchor_rejections`만 추가한다. 6→7과 기존 1/2→7 경로는 기존 행·열·원문/hash를 보존한다. 향후 백업·복원은 새 두 테이블과 명령 v4/typed 거절을 포함해야 한다. OS 알림 권한/예약은 백업 금융 결과에 포함하지 않고 기존 dirty 재조정 계약을 유지한다. 미제출 초안과 잠긴 제출 참조는 구분하며 실제 백업 기능은 별도 작업이다.

## 화면과 검증

표시 데이터와 자식은 `AccountDetailUiState.kt`에 두고 진행 상태·enum은 별도 State 파일에 둔다. ViewModelScope와 Hilt 주입 Clock/dispatcher를 사용하며 snapshot 투영·영향 계산은 default dispatcher에서 수행한다. feature 소유 Nav2 상세 목적지, lifecycle 수집, 안정적인 목록 key를 사용한다. 미제출 원문은 기존 DraftEditor/Room에 보관하고 새 진입에서는 사용자가 초안을 직접 선택한다. 저장 결과 불명 상태에서는 입력을 바꾸거나 새 ID로 제출하지 않는다.

Figma 대응: [자산 소계 A13](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=349-1849), [미확인 상세 A14](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=349-1904), [확인 상세 A15](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=349-1954), [기준액 입력 F01](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=129-1002), [최초 검토](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=349-2003), [변경 검토](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=349-2053). 합성 예시 수치는 실제 snapshot 값으로 대체한다. OS 상태/탐색 바 이미지는 제품 UI 자산으로 넣지 않는다.

모델 계산, 파일 Room 멱등·충돌/rollback·마이그레이션, ViewModel 초안/응답 유실, 실제 Nav2/Compose 입력·Back·최종확인과 Native Canvas 렌더를 검증한다. 호스트 렌더는 실기기·IME·프로세스 종료 검증을 대신하지 않는다. 실기기 시험은 사용자가 재개할 때 별도로 수행한다.
