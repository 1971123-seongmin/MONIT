# 거래 탐색 계약

거래 조회와 신규 입력의 타입 경로·공개 진입 함수·화면 등록 함수를 제공한다. 신규 입력은 `entryId`·초기 유형·선택적인 ISO 날짜만 전달한다. `entryId`는 거래 레코드 ID가 아닌 입력 화면 인스턴스 ID다. 호출 feature가 새 입력을 열 때 만들고 route와 폼에서 함께 보존한다. 실제 입력 폼·장부 context·금융 제출을 경로에 넣지 않는다. 제품 화면과 ViewModel 연결은 후속 단계다.

등록 화면에서 돌아온 `RegisteredAccountResult`는 요청 ID와 계좌 ID를 가진 UI 후보다. `RegisteredAccountResultEffect`는 호출 entry가 RESUMED일 때 수신 콜백에 전달한다. 콜백은 폼·선택 슬롯·장부 세대(현재 저장소 context의 세대)를 검증하고 후보 반영 또는 기존 소비를 확인한 경우만 true를 반환한다. false면 키를 유지하며 콜백이 수신 거부 상태를 표시한다. 자료 형식·요청 ID 오류는 `onInvalidResult`에서 계좌 자동 적용을 막고 사용자가 확인할 복귀 상태를 표시한다. 이 기반 구현에는 해당 제품 검증 UI가 없다.

콜백의 반환값이 false여도 자동 재시도하지 않는다. 같은 키의 새 결과 방출이나 entry의 다음 RESUMED 수집 시작에 다시 전달할 수 있으므로 수신 처리는 멱등이어야 한다. 콜백 교체나 단순 재구성만으로 결과를 다시 전달하지 않는다.

선택을 새로 요청할 때마다 새로운 `selectionRequestId`를 만들고 폼과 함께 보존한다. 결과 키는 요청마다 다르다. `SavedStateHandle.remove`로 제거된 Flow를 같은 요청의 다음 전달 채널로 재사용하지 않는다. 콜백은 최신 재구성 값을 사용하며 controller나 source를 공유 singleton에 보관하지 않는다.

결과 소비는 계좌 자동 적용·추가 탐색·금융 재제출·영속 참조 정리를 뜻하지 않는다. 해당 책임과 후속 순서는 [전체 탐색 설계](../../docs/architecture/navigation2-navigation-plan.md), 공통 실행 계약은 [core:navigation](../../core/navigation/README.md)에 있다.
