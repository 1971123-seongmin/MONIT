# 거래 수정·논리 삭제

## 진입과 화면

거래 달력의 선택일 목록 또는 월 목록에서 거래를 선택하면 같은 Nav2 입력 경로의 상세 모드로 진입한다. 경로에는 거래 ID만 추가하며 값과 revision은 현재 장부 snapshot에서 읽는다. 상세 조회·Back은 초안이나 금융 명령을 만들지 않는다. `수정하기`를 선택하면 기존 입력 검증·계좌 선택·초안 보관을 사용하고, 저장 결과를 확인한 뒤 원래 caller로 복귀한다. 복귀한 탭은 기존 lifecycle 재조회로 목록·달력·월 합계를 갱신한다.

삭제는 상세 화면의 영향 안내와 `삭제 확인` 후에만 제출한다. 달력·목록·계좌 잔액·월 합계에서 제외하며, 연결된 납입은 같은 거래 트랜잭션에서 활성 연결을 해제하고 재확인으로 바꾸며 이전 근거를 보존한다. 금융값 수정·메모 변경의 차이도 [납입 계약](../home/STATE.md)을 따른다. 삭제 후 복원·자동 만료·물리 삭제는 제공하지 않는다.

## 저장과 이력

- Room v4는 기존 v3에 nullable `transactions.deletedAt`, append-only `transaction_history`, `submission_rejections`만 추가한다. 이전 schema와 v1→2→3→4 경로를 유지하며 과거 이력을 역생성하지 않는다.
- 수정·삭제 직전 전체 값, 변경 operationId·시각·종류를 이력에 보관한다. 현재 행은 삭제 후에도 남아 있어 과거 생성·수정 영수증을 검증할 수 있다.
- expectedRevision은 금융 트랜잭션 안에서 확인한다. 메모만 변경하면 객체 revision만, 금융값 변경·최초 삭제는 financialRevision도 증가한다. 이체는 단일 행의 양쪽 효과를 함께 변경한다.
- 이전 값·현재 행·ledger revision·성공 원장·pending 소비·연결 초안 소비는 같은 트랜잭션이다. 변경 전/후 월과 현재 잔액의 계산도 같은 writer 스냅샷에서 검사한다.
- 생성 명령의 canonical v1 원문/hash를 그대로 유지하고 수정·삭제만 expectedRevision이 있는 v2로 저장한다. 같은 operationId/내용은 기존 결과, 다른 내용은 충돌이며 자동 새 ID나 revision 치환은 없다.

## 확정 충돌과 제출 복구

기존 성공만 종료하던 계약을 확정된 대상 revision 충돌에 한해 확장했다. 성공/기존 거절을 먼저 검사하고, 수정·삭제 대상의 revision 불일치 또는 이미 삭제된 상태가 확인되면 금융 변경 전 같은 writer 트랜잭션에서 거절을 commit한다. 성공 영수증을 만들지 않으며 ledger revision·거래·이력은 변경하지 않는다. I/O·취소·장부 CAS 실패·다른 저장 세대·미지원·손상은 확정 대상 충돌로 바꾸지 않는다.

거절 결과는 원래 요청 원문/hash·context·operationId·대상·expected/actual revision·관찰한 삭제 시각·거절 시각을 보존한다. 연결 초안은 `REJECTED`로 닫되 원문을 유지한다. 새 독립 입력을 막거나 자동 재제출하지 않는다.

복구 UI는 반영되지 않았다는 결과를 별도로 표시한다. 사용자가 결과 확인을 마치면 선택한 활성 참조만 정리한다. 원문·거절 결과·초안·금융 이력은 삭제하지 않는다. 종료 응답 유실 뒤 재요청은 `AlreadyInactive`이며, 남은 원문이 LegacyPending으로 재발견되거나 참조가 부활하지 않는다. 새 수정은 목록에서 최신값을 읽고 사용자가 다시 시작한다.

## 검증 경계

`TransactionChangesTest`는 합성자료의 실제 파일 Room에서 수정·삭제·revision 경합·취소/SQL rollback·거절 복구/종료·원문 보존·v3→4 Migration을 검증한다. ViewModel 시험은 초안 문맥·SavedState·연타·응답 유실·확정 거절을, `EntryJourneyTest`는 실제 Route/VM/Nav2의 수정·삭제 확인·Back·caller 복귀를 검증한다.

호스트 UI 시험은 실제 S23·시스템 IME·프로세스 강제 종료·전원 중단·release 성능 측정과 구분한다. 실제 사용자 금융자료 및 Notion 이관 writer·원격 스키마는 이번 범위에 포함하지 않는다.
