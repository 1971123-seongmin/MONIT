# 홈 조회와 탐색

`HomeRoute`는 entry 범위 Hilt ViewModel을 사용해 실제 Repository의 서울 오늘 기준 snapshot을 읽는다. 월 전체 수입·지출은 이체를 제외한 저장소 합계이며 조회 실패를 0원으로 바꾸지 않는다. `HomeScreen`은 상태와 콜백만 받는 순수 Compose UI다. 납입 일정 API·확인 행동은 아직 제공하지 않는다.

`HomeDestination`·`navigateToHome`·`homeScreen`은 feature의 탐색 계약이다. app이 검증한 장부 context를 전달하며 화면 재개 시 다시 조회한다. 계산·표시 준비는 주입한 default dispatcher에서 수행한다. Preview는 고정 날짜와 빈 UI 자료를 사용하며 금융 성공 증거가 아니다.

탭·Back 정책은 app의 MainNavigator, 실행·수명 검증은 [core:navigation](../../core/navigation/README.md)이 소유한다.
