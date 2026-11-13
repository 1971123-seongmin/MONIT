# 계좌 조회와 등록 기반

`list/AccountsRoute`는 app이 확인한 장부 context와 entry 범위 Hilt ViewModel을 사용해 실제 snapshot을 읽는다. `AccountsScreen`은 순수 표시 자료와 조회 재시도 콜백만 받는다. 화면 재개 시 원본을 다시 읽고, 표시 준비는 주입한 default dispatcher에서 수행한다.

현재 합계는 활성 계좌의 확인 가능한 장부 잔액만 포함한다. 잔액 미확인은 0원과 구분하고 합계 제외를 안내한다. 보관된 계좌는 상태를 표시하며 현재 합계에서 제외한다. 은행 확인 금액이나 실시간 평가금액으로 표현하지 않는다.

계좌 등록과 상세 목적지, 기준일 마감액 입력→영향 검토→최종 확인, 이력 및 선택 월 저축·투자 납입/인출/순납입을 연결한다. [등록 상태 계약](STATE.md), [계좌·자산 계약](ASSETS.md), [전체 탐색 설계](../../docs/architecture/navigation2-navigation-plan.md)를 따른다. 실제 은행 연결·시장 평가·수익률과 일반 기준일 변경은 제공하지 않는다.
