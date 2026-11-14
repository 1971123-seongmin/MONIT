# 거래 조회와 탐색

`TransactionsRoute`는 검증된 장부 context와 entry 범위 Hilt ViewModel을 사용한다. `TransactionsScreen`은 순수 표시/선택 자료와 콜백을 받는다. 최초 서울 현재 월·오늘, 일요일 시작 달력, 월말 보정, 오늘 이동과 달력/월 전체 목록 전환을 제공한다. 월·날짜·보기·종류/계좌 필터는 SavedStateHandle의 primitive 값으로 보존한다.

실제 Repository snapshot은 화면 재개·명시 재시도·월 변경에서 읽는다. 같은 월의 날짜/보기/필터 전환은 최근 성공 snapshot을 default dispatcher에서 다시 투영하며 새 DB 조회를 만들지 않는다. 이것은 VM 수명의 표시 캐시이며 Room 원본이나 영속 초안을 대신하지 않는다. 요청 교체는 이전 Job을 취소하고 늦은 완료의 반영도 차단한다. 실패는 정상 빈 결과와 구분한다.

월 전체 합계는 필터 적용 전 범위를 명시하고 이체를 제외한다. 달력에는 수입/지출의 요약 금액·이체 건수, 접근성 설명과 목록에는 정확한 원화 금액을 제공한다. 미래 날짜는 조회할 수 있다. 신규 입력·계좌 등록·영속 초안/제출 복구는 [입력 계약](ENTRY.md), 수정·논리 삭제는 [변경 계약](STATE.md)에 따라 제품 경로에 연결돼 있다.

거래 조회와 신규 입력의 타입 경로·공개 진입 함수·화면 등록 함수를 제공한다. 신규 입력은 `entryId`·초기 유형·선택적인 ISO 날짜만 전달한다. `entryId`는 거래 레코드 ID가 아닌 입력 화면 인스턴스 ID다. 호출 feature가 새 입력을 열 때 만들고 route와 폼에서 함께 보존한다. 실제 입력 폼·장부 context·금융 제출을 경로에 넣지 않는다. 실제 입력/등록 화면과 ViewModel을 연결했으며 금융값은 Repository에서 다시 검증한다.

등록 화면에서 돌아온 `RegisteredAccountResult`는 요청 ID와 계좌 ID를 가진 UI 후보다. `RegisteredAccountResultEffect`는 호출 entry가 RESUMED일 때 수신 콜백에 전달한다. 콜백은 폼·선택 슬롯·장부 세대(현재 저장소 context의 세대)를 검증하고 후보 반영 또는 기존 소비를 확인한 경우만 true를 반환한다. false면 키를 유지하며 콜백이 수신 거부 상태를 표시한다. 자료 형식·요청 ID 오류는 `onInvalidResult`에서 계좌 자동 적용을 막고 사용자가 확인할 복귀 상태를 표시한다. 제품 입력 UI는 현재 요청·슬롯·세대와 재조회된 활성 계좌를 검증하고 사용자의 명시 적용을 요구한다.

콜백의 반환값이 false여도 자동 재시도하지 않는다. 같은 키의 새 결과 방출이나 entry의 다음 RESUMED 수집 시작에 다시 전달할 수 있으므로 수신 처리는 멱등이어야 한다. 콜백 교체나 단순 재구성만으로 결과를 다시 전달하지 않는다.

선택을 새로 요청할 때마다 새로운 `selectionRequestId`를 만들고 폼과 함께 보존한다. 결과 키는 요청마다 다르다. `SavedStateHandle.remove`로 제거된 Flow를 같은 요청의 다음 전달 채널로 재사용하지 않는다. 콜백은 최신 재구성 값을 사용하며 controller나 source를 공유 singleton에 보관하지 않는다.

결과 소비는 계좌 자동 적용·추가 탐색·금융 재제출·영속 참조 정리를 뜻하지 않는다. 해당 책임과 후속 순서는 [전체 탐색 설계](../../docs/architecture/navigation2-navigation-plan.md), 공통 실행 계약은 [core:navigation](../../core/navigation/README.md)에 있다.
