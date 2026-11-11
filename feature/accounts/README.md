# 계좌 조회와 등록 기반

`list/AccountsRoute`는 app이 확인한 장부 context와 entry 범위 Hilt ViewModel을 사용해 실제 snapshot을 읽는다. `AccountsScreen`은 순수 표시 자료와 조회 재시도 콜백만 받는다. 화면 재개 시 원본을 다시 읽고, 표시 준비는 주입한 default dispatcher에서 수행한다.

현재 합계는 활성 계좌의 확인 가능한 장부 잔액만 포함한다. 잔액 미확인은 0원과 구분하고 합계 제외를 안내한다. 보관된 계좌는 상태를 표시하며 현재 합계에서 제외한다. 은행 확인 금액이나 실시간 평가금액으로 표현하지 않는다.

기존 `AccountRegistrationViewModel`·등록 상태·탐색 계약은 유지한다. 등록·기준액 확인·수정/삭제 화면은 아직 연결하지 않았으며 작동하지 않는 추가 버튼이나 빈 목적지를 제공하지 않는다. [등록 상태 계약](STATE.md)과 [전체 탐색 설계](../../docs/architecture/navigation2-navigation-plan.md)를 따른다.
