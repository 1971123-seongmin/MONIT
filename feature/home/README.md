# 홈 조회와 탐색

`HomeRoute`는 entry 범위 Hilt ViewModel을 사용해 실제 Repository의 서울 오늘 기준 snapshot을 읽는다. 월 전체 수입·지출은 이체를 제외한 저장소 합계이며 조회 실패를 0원으로 바꾸지 않는다. `HomeScreen`은 상태와 콜백만 받는 순수 Compose UI다. 이번 달 납입 요약과 월별 등록·수동 확인·정정은 [구현 상태](STATE.md)를 따른다.

`HomeDestination`·`navigateToHome`·`homeScreen`은 feature의 탐색 계약이다. app이 검증한 장부 context를 전달하며 화면 재개 시 다시 조회한다. 계산·표시 준비는 주입한 default dispatcher에서 수행한다. Preview는 고정 날짜와 빈 UI 자료를 사용하며 금융 성공 증거가 아니다.

탭·Back 정책은 app의 MainNavigator, 실행·수명 검증은 [core:navigation](../../core/navigation/README.md)이 소유한다.

홈의 알림 설정·일정별 선호·권한 복귀와 월별 조회 진입은 [알림 구현 상태](REMINDERS.md)를 따른다. 실제 금융 완료와 OS 알림·호스트/실기기 검증을 구분한다.
