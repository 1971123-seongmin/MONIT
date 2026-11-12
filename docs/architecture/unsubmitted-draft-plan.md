# 미제출 입력 초안의 영속 복구

상태: **2026-11-12 구현·로컬 자동 검증 완료, 최신 CI·S23 수용시험 대기**. 기존 데이터 보존·초안 테이블 추가·DB schema2→3 Migration과 기존 Repository 안의 최소 원자적 저장 구조를 구현했다. Room 라이브러리 버전 변경은 아니다. 아래 설계와 시험 조건은 실제 구현·검증 결과와 구분한다.

기준 코드는 main c8a81366의 Room schema2·ENTRY/STATE·typed 제출/영속 발견 API다. 500ms/flush/마지막 committed revision은 PM이 채택한 이번 구현의 기술 기준이며 별도 사용자 승인이나 기기 시험 통과를 뜻하지 않는다. 과거 대형 작업본은 복사하거나 현재 구현의 원본으로 사용하지 않는다.

## 저장과 제출 경계

- schema3에 form_drafts 한 테이블을 비파괴 추가한다. 금융 행·pending·operation·참조와 기존 v1 원문/hash 형식은 보존한다. 초안은 금융 집계·ledger revision·파일 백업에 넣지 않는다.
- 초안은 장부/저장 세대·독립 폼 ID·폼 종류·진입 출처·부모 초안/계좌 선택 요청/슬롯을 구분한다. NavBackStackEntry 객체를 저장하지 않는다. 금액·날짜·선택 계좌 ID·메모·계좌 이름/기준일 원문을 값 보정 없이 저장한다.
- content revision을 비교하는 CAS로 이전 autosave의 덮어쓰기를 막는다. 폐기/소비 시 내용은 비우고 ID/revision/종료 fence를 보존해 늦은 저장이 초안을 되살리지 못하게 한다. 자동 만료는 없다.
- 첫 유효 금융 제출은 flush → 원문과 revision 확인 → 같은 operationId의 제출 잠금/기존 pending·참조 영속화 → 금융 트랜잭션 순서다. 금융 변경·operation 결과·pending 소비·해당 초안 소비는 한 트랜잭션이다. retryPending도 연결된 초안을 같은 commit에서 소비한다.
- 이미 제출한 초안은 편집·폐기·새 ID 전환을 허용하지 않는다. 과거 문구의 ‘미확정 폐기 후 새 ID’/‘초안으로 돌아가기’는 pending 보존과 충돌하는 별도 정책으로 구현하지 않는다. 기존 receipt 조회와 동일 payload/ID 재시도만 유지한다.

## 앱과 UI

- 새 모듈 없이 core:model의 DraftRepository 계약과 core:data의 Room 구현을 추가한다. 기존 LedgerRepository 계약은 유지하고 같은 DB의 금융 writer에 초안 잠금/소비 경계를 연결한다.
- 두 ViewModel의 반복되는 debounce/flush/CAS/committed revision 처리는 core:common의 코루틴 coordinator로 공유한다. 기본 viewModelScope를 사용하며 새 전역 scope나 UseCase를 만들지 않는다.
- 입력은 500ms 후 저장하고 focus 이탈·명시 이동/나가기·제출 전에 가능한 flush를 수행한다. 마지막 DB commit이 확인된 revision만 표시하고 더 최신 입력은 저장 대기/실패로 구분한다. 500ms를 디스크 지연까지 포함한 최대 유실 시간으로 보장하지 않는다.
- 재진입은 마지막 DB 초안 목록에서 명시 복구 또는 미제출 초안 명시 폐기를 제공한다. 다른 세대/폼/호출 문맥은 실행하지 않는다. 부모 거래를 복구한 뒤 명시 계좌 추가에서 그 요청의 자식 초안을 복구한다. 독립 계좌나 다른 거래에 후보를 적용하지 않는다.
- 금융 제출 복구는 기존 EntryRecoveryGate가 우선하며 금융 참조 종료와 미제출 초안 폐기는 별개다. SavedState는 작은 초안/호출 참조 및 기존 task 입력/제출 캡처를 호환성 보조 상태로 유지하며 영속 복구의 근거는 Room이다.

## 검증과 완료 경계

실제 파일 Room 시험으로 schema2 데이터 보존·재열기·원문/revision·CAS·폐기 뒤 늦은 저장·제출 잠금·첫 제출 기록 실패·금융 rollback/소비 원자성·응답 유실/동일 재시도·다른 세대 차단·금융 집계 제외를 확인한다. 가상 시간/gate로 debounce·flush 순서·늦은 응답·오류·중복을 검증하고 실제 Route/VM/Nav2의 복구·폐기·계좌 등록 왕복을 확인한다.

앱 삭제·기기 분실·DB 자체 손상은 로컬 복구 보장 밖이다. 강제 종료·재부팅과 실제 UID/자료 분리 시험은 QA가 별도 QA 앱에서 수행한다. 기존 QA APK·자료를 변경/초기화하지 않으며 새 산출물은 새 hash/head로 인계한다. 서버·동기화·암호화 라이브러리·원격 schema·백업 형식·무관한 모듈 개편은 포함하지 않는다. 구현·자동 시험·실기기 검증 결과를 각각 구분한다.

## 승인 후 필요한 최소 시험

| 경계 | 조건과 제어 | 관찰할 기대 결과 |
| --- | --- | --- |
| 비파괴 Migration | schema2 파일에 금융 행·operation·pending·참조를 둔 뒤 업그레이드/재열기 | 기존 행/원문/hash/ID/세대 보존, 초안만 금융 집계와 분리 |
| 원문과 마지막 commit | 잘못된 금액/날짜·공백·선택 계좌·메모를 저장하고 새 Repository로 재열기 | 값 보정 없이 원문/revision 복구, 금융 합계/ledger revision 불변 |
| debounce/flush | 단일 TestScheduler의 499ms/500ms 경계와 writer gate, focus/이동/제출 flush | 최신 예약만 저장, commit 전 revision을 완료로 표시하지 않음, flush 전 금융 writer 0 |
| CAS/늦은 응답 | 이전 autosave 대기 중 새 입력, 두 편집자의 동일 revision 경쟁 | 새 입력을 이전 응답이 덮어쓰지 않음, 충돌한 편집을 자동 재적용하지 않음 |
| 명시 폐기와 fence | 폐기 전/후 autosave 순서를 gate로 각각 실행 | 사용자 확인 없는 폐기 없음, 폐기 후 내용/초안 부활 없음, 다른 초안·금융 기록 보존 |
| 제출 첫 단계 | 초안 flush·revision 검사·제출 잠금/원문/참조 기록의 실패·취소 | 첫 단계 미확정이면 금융 쓰기 0, 잠긴 초안의 편집/폐기 차단, 원래 operationId 유지 |
| 금융 원자성 | 실제 SQLite trigger로 결과 기록 또는 초안 소비를 실패시키고 재열기 | 금융 행/operation/초안 소비가 함께 rollback, 원래 pending/참조와 잠금 보존 |
| commit 뒤 응답 유실 | 성공 commit 직후 응답을 잃고 원래 ID로 조회/재시도 | 금융 행·ledger 증가 한 번, 소비된 초안 부활 없음, 기존 영수증 재사용 |
| 세대/폼/caller | 다른 저장 세대·폼 종류·부모 초안·선택 요청/슬롯을 전달 | 다른 문맥의 초안을 적용/소비하지 않음, 원본 보존·차단 안내 |
| 명시 UI 복구 | SavedState 없이 재진입, 복수 초안, 부모 거래와 자식 계좌의 복구/폐기 | 명시 선택한 초안만 복구/폐기, 반대편 계좌/입력 보존, 새 금융 자동 제출 없음 |

자동 시험은 실제 파일 Room/SQL 취소·오류/경합, TestScheduler coordinator, ViewModel, 실제 Route·Nav2 UI 계층으로 나눴다. 전체 단위/통합/UI 281개가 실패·오류·skip 없이 통과했다(신규 40개). ktlintCheck·debug/QA APK 빌드·두 variant Lint·기존 CI 분류 31시험·문서 26검사도 통과했다. 최종 부분 SavedState 차단 보완 뒤 영향받은 계좌 39·거래 23·앱 60시험과 APK/Lint를 다시 확인했다. 다른 모듈의 소스/의존 입력은 같아 앞선 결과가 유효하다. source SHA·원격 CI와 APK hash는 해당 PR에 기록하며 실제 S23 강제 종료/재부팅·복구 결과와 구분한다.

최종 APK는 기존 `com.monit.app.qa`와 동일 applicationId/서명으로 별도 전달한다. QA가 기존 schema2 합성 계좌/거래를 보존한 업데이트 설치 후 기존 합계/ID·초안 원문·명시 복구/폐기·부모/자식 호출·제출 중단 재시도를 확인한다. 기존 QA 데이터 삭제·초기화·재설치나 제품 `com.monit.app` 자료 조작은 하지 않는다. 자동 시험·최신 CI뿐 아니라 S23 수용시험 결과 확인도 병합 선행 조건이며 최종 병합은 PM이 단독 수행한다. 다음 제품 작업은 병합 뒤 한 건만 배정한다. CodeRabbit은 계속 OFF다.
