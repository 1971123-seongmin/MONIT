# 이관 검토의 원자 반영 설계안

Issue #68. 기준 main `02f767e`. **PM 기술 선택 확정(2026-11-13)**. Room9 별도 이관 요청/결과·행/대응/fence/child 연결, 기존 원문 BLOB 재사용, 금융 내부 primitive·최종 집합 검증, 논리 백업 v3를 구현했다. [로컬 검증과 미검증 범위](../qa/import-writer-host.md)를 구분하며 최신 CI/병합 판단은 연결 PR을 따른다. 합성 fixture의 구현·시험만 승인됐으며 실제 자료 조회/판정/원본 쓰기·기기 작업은 보류다.

## 목표와 기존 코드

- 현재 추출 envelope v1과 미리보기의 strict UTF-8/중복키/hash/관계/통화 검증을 재사용한다. 일반 원격 응답·CSV를 자동 해석하는 새 adapter는 만들지 않는다.
- 선행 #56은 `EXISTS=0`, `actualCreatedCount=0`인 읽기 전용이었다. 이번 writer는 실제 source mapping과 현재 대상의 revision/payload를 대조해 EXISTS를 생성한다. 미리보기의 실제 생성 수는 계속 0이며 확정 결과의 실제 생성 수는 별도 `ImportResult`에서 읽는다.
- 새 `ImportRepository`를 기존 core:model/core:data 경계에 둔다. 원본 검증·판정 모델은 core:model, parser/평가/Room writer는 core:data로 이동·재사용한다. feature:accounts에는 순수 UiState·화면·명시 사용자 판단과 ViewModel을 둔다. 한 줄 UseCase·새 Gradle 모듈·범용 실행 엔진은 추가하지 않는다.
- 앱은 SAF와 #67 백업 화면의 결과 전달, 기존 작업 lease/구조화된 취소를 연결한다. 금융·원문·URI·암호는 navigation/SavedState에 넣지 않고 조회용 batch/operation ID와 장부 context만 허용한다.

## 미리보기와 검토 토큰

1. 선택한 파일의 정확한 bytes/digest를 읽고 기존 envelope를 파싱한다. 원본 파일은 수정하지 않는다. 표시 이름은 식별 키나 경로로 쓰지 않는다.
2. 원본 행의 namespace/entity/id 또는 무ID 파일의 `fileDigest + namespace/entity + 원래 배열 위치 + locator`를 구조화된 canonical 식별자로 만든다. mapping fingerprint는 별도로 보존하고 같은 snapshot+locator 이력을 대조한다. 파일 재내보내기·순서 변경의 동일성을 추정하지 않는다.
3. 현재 계좌/기준액/거래·삭제 상태와 source mapping을 같은 read snapshot으로 확인한다. 이미 반영한 출처와 현재 target revision/payload가 모두 같은 경우만 EXISTS다. 보관 계좌의 과거 정상 거래와 신규 선택 가능 여부를 구분한다.
4. 원본 변경·매핑 결과 변경·로컬 수정/삭제·현재 장부 밖의 이전 반영 fence는 HELD다. 자동 업데이트/부활/소거는 하지 않는다. ID 없는 같은 날짜/금액은 후보일 뿐이며 별개 거래 확인은 원본 행별 명시 판단에 결합한다.
5. 이체 두 행은 사용자가 선택한 정확한 원본 집합만 묶는다. 날짜/금액 일치는 검증 조건이며 짝 찾기 기준이 아니다. 출금·입금 관계가 하나씩 확정되고 통화·금액·대상 계좌/일자가 일치해야 한다. 불명확하거나 한쪽만 있으면 보류한다.
6. 전환일과 계좌별 기준일/기준액 확인 또는 유지 결정을 명시한다. 기존 고정 기준일을 이관 과정에서 바꾸지 않는다. 새 계좌가 필요하면 기존 계좌 등록 흐름을 거친 뒤 새 revision에서 재검토한다. 이름으로 계좌를 생성·합치지 않는다.
7. 토큰은 파일/snapshot/mapping digest, parser·변환 버전, context/generation/대상 revision, 계좌·기준액 own revision, 전환일·확인값, 행별 판정·명시 이체 집합·생성 ID/명령 계획에 결합한다. 구조화된 canonical JSON hash를 사용하며 구분자 문자열 연결에 의존하지 않는다.

미리보기의 예정 행 수와 실제 반영 수를 분리한다. `읽은 행 = 반영 + 이미 존재 + 보류 + 제외`는 commit 결과에 적용한다. 이체 두 원본 행은 반영 2/생성 거래 1이다. 통화별/월별/유형별 금액과 계좌 효과를 분리하며 외화를 KRW에 더하지 않는다. 미지원/보류가 남으면 부분 지원 결과로 표시한다.

## 최소 저장안 — Room 8→9

기존 금융 테이블은 유지하며 다음 테이블을 추가한다. 실제 열/DDL은 `ImportRecords.kt`, `LedgerMigration8To9.kt`와 checked-in Room schema9에 고정한다.

| 저장소 | 책임 |
|---|---|
| `import_batches` | batch/operation ID, 원래 context, immutable 요청/raw hash·검토 fingerprint, 대상 revision, 원본 문서 참조, REQUESTED/COMMITTED 등 구분, immutable 결과·확인 상태 |
| `import_rows` | 원래 행 위치/locator·출처, raw digest, 판정·사유·이체 group/대상 참조. 예정과 실제 반영을 구분 |
| `import_source_mappings` | 안정적 출처 키→생성 target 집합, 마지막 적용 payload/hash·객체/금융 revision·원본 문서/행·mapping 버전. 논리 삭제 후에도 남김 |
| `import_child_commands` | 묶음과 실제 금융 child operation 연결. canonical v1~v4 원문/영수증을 재사용하고 묶음 단위 복구/검증에 연결 |
| `import_source_fences` | 원래 context/출처 키·digest와 이전 반영 target의 최소 이력. 현재 장부 밖의 이력도 자동 신규/부활로 오인하지 않도록 보존 |

원본 bytes는 새 BLOB 저장소를 만들지 않고 기존 `backup_originals`를 재사용한다. 전체 추출 bytes를 그대로 저장하고 행별 원문은 원본 문서+행 위치+hash로 대조한다. 미지원/제외 행도 원문을 잃지 않는다. 안정적 ID의 identity에는 mapping 버전을 섞지 않는다. 무ID identity는 파일/원래 위치/locator를 결합하고 mapping 변경은 별도 이력으로 보류한다. 2MiB 원문과 큰 root 요청은 128KiB 바이트 단위로 읽어 CursorWindow의 단일 행 한도와 UTF-8 중간 경계를 보존한다.

출처 fence는 활성 금융 FK와 분리하고 전체 복원에서도 현재/입력 집합의 합집합을 유지하는 안을 권장한다. 이전 반영 이력이 현재 장부/복원 파일에 없으면 EXISTS나 신규로 추정하지 않고 재검토 대상으로 남긴다. 무기한 이력을 자동 정리하지 않는다.

기존 미리보기의 8MiB 파일·10,000행 읽기 지원과 실제 원문 보관 가능 범위는 구분한다. 반영은 백업의 원문 합계 2MiB/32개, 전체 8MiB/50,000레코드 한도에 새 원본/대응/명령/이력까지 포함해 사전 검사한다. 초과하면 반영하지 않으며 자동 생략·잘라내기로 맞추지 않는다.

## 원자 writer와 실행 근거

권장안은 원래 요청의 별도 `monit-import-1` 코덱과 묶음 결과를 두고, 금융 child의 기존 canonical v1~v4는 재작성하지 않는 방식이다. 전역 기존 WriteCodec에 임의 root kind를 끼워 넣지 않는다.

- 최종 확인 뒤 원본/검토 계획/요청 ID를 immutable REQUESTED로 캡처한다. 이 단계는 원문·실행 근거 보존이며 금융 반영 성공이 아니다. URI는 저장하지 않는다.
- 현재 세대/revision, 토큰과 명령 ID, 출처 중복/로컬 revision, 적용 전 백업 증거를 금융 transaction 입구에서 재검사한다. 지원/검토 완료 집합 외의 임의 payload를 받지 않는다.
- 한 Room transaction 안에서 확정한 기준액/거래, 원본 대응, 행 판정·group, 금융 child 원문/영수증, 묶음 결과/COMMITTED와 ledger revision·알림 dirty를 함께 확정한다. 개별 행별 독립 commit은 없다.
- 금융 삽입/기준액 적용의 기존 검증을 작은 내부 primitive로 재사용하고 최종 집합의 잔액 경로/합계를 검사한다. 기존 단일 입력은 계속 즉시 검증한다. 단순히 공개 writer를 반복 호출하는 안은 채택 전 검토가 필요하다: 두 계좌가 각각 금액 상한인 상태의 같은 날 반대 방향 이체 1씩은 최종 집합은 유효하지만 어느 한 행을 먼저 단독 검증해도 임시 상한을 넘기 때문이다.
- SQL 오류/취소 전에는 활성 금융·대응·결과가 모두 rollback한다. REQUESTED는 미반영 요청으로 구분해 보존한다. 실제 작업/정리 종료 전 새 실행을 허용하지 않는다.
- commit 뒤 응답 유실은 batch/operation ID의 영속 결과를 조회한다. UI 재구성/복귀/새 프로세스가 재반영하지 않는다. REQUESTED만 있거나 context/revision이 바뀌면 재검토로 안내하며 자동 최신화·자동 실행하지 않는다.
- child 복구 항목을 개별 입력 화면에 흩어 노출하지 않고 묶음 단위 복구 adapter로 연결한다. COMMITTED root와 child의 존재/원문/hash/결과 연결을 확인한다. 기존 독립 입력 복구 의미와 원문/참조 존재 여부는 보존한다.

## 적용 전 백업과 화면

`파일 선택 → 판정/계좌·기준 확인/명시 묶음 검토 → 현재 장부 백업 → 최종 영향 확인 → 반영 → 결과·대사` 순서를 제안한다. 백업은 #67의 암호 입력/SAF 쓰기·close·재읽기 검증을 사용한다. 마지막 성공 시각만 보고 통과시키지 않는다.

Room9의 `backup_status`에 검증한 export ID·context·ledger revision을 보존하고 검토 토큰의 대상과 일치해야 반영한다. 백업 이후 금융/세대 변화나 파일/매핑 변화는 재검토·필요한 새 백업으로 이어진다. 동일 금융 revision에서 새로 캡처한 미반영 REQUESTED는 금융 사본의 신선도를 바꾸지 않으며 그 원본/요청은 별도로 보존한다.

진행·취소 요청/실제 종료 대기·재검토·부분 지원·확정 결과 불명을 별도 State로 둔다. UiState에는 표시 데이터만 두며 enum/명령·판정 로직을 넣지 않는다. parser/계산/파일 작업은 주입 dispatcher에서, UI는 lifecycle 수집·안정 key를 사용한다. 검증 30초/파일 60초 협력 중단과 실제 종료 전 중복 차단을 재사용한다.

## 확정한 백업 호환성

이전 strict v2는 이관 묶음/행/대응/fence를 정의하지 않는다. 필드를 조용히 추가하거나 복원 때 버리지 않는다.

외부 envelope1/suite1은 유지하고 논리 format/contract3를 별도 schema로 정의한다. strict v1/v2 reader는 유지한다. 새 작성은 v3이며 원래 canonical v1~v4/raw hash는 그대로다. 이관 원본/묶음·대응/child 결과/삭제 흔적과 source fence를 검증·보존하고 복원된 요청은 evidence-only다. 이전 v2에 없는 이관 정보는 `NOT_RECORDED_BY_V2`로 구분하고 새 이관을 했다는 이유로 과거 미기록을 RECORDED로 바꾸지 않는다. 백업 세부 계약의 원본은 [core/data/BACKUP.md](../../core/data/BACKUP.md)다.

출처 fence가 있는 복원 후에는 mapping이 현재 파일에 없다는 이유로 과거 source를 자동 신규로 반영하지 않는다. 현재/복원 묶음·원본·child·금융 참조의 일관성은 최종 단일 복원 transaction에서 유지한다. 복원/이관의 물리 DB 교체는 없다.

## 확정된 기술 선택

1. 별도 import 요청/결과와 기존 금융 primitive·canonical child 재사용, Room9의 최소 테이블/백업 증거 열을 채택한다. 단건 금액·참조·계좌·날짜·P2·anchor CAS를 유지하고 최종 집합의 잔액 경로·집계·overflow를 결정적으로 사전 검증한다.
2. 계좌마다 기존 고정 기준일 C를 유지한다. 서로 다른 C를 하나로 강제하지 않는다. 기준액 유지(미확인 포함) 또는 명시 ConfirmAnchor를 같은 반영 집합에서 확정하고, 새 계좌는 기존 명시 등록 후 재검토한다.
3. 권장 A인 logical formatVersion3/contractVersion3와 strict v1/v2 reader 유지를 채택한다. v2에 없던 이관 근거는 미기록 coverage로 구분한다. 현재/복원 source fence를 합치되 동일 활성 mapping은 EXISTS가 가능하고 mapping 부재/다른 내용/context 충돌은 보류 또는 무쓰기 거부한다.
4. 동일 snapshot+locator인 무ID 원본은 mapping 버전을 바꿔 새 ID로 중복 우회하지 못한다. mapping 변경 이력 대조는 identity와 별도로 유지하고, 순서 변경/다른 파일은 명시 후보 판단을 요구한다.

## 검증과 인계

- V9: 같은 출처/내용 신규0, 원본 변경·로컬 메모/금융 수정·삭제 충돌, 보관 계좌 과거 유지/신규 제한.
- V10: 같은 날짜/금액 무ID 두 행을 자동 합치지 않음, 별개 확인 후 두 거래, 같은 파일 재시도0·다른 파일/순서 변경은 별도 검토.
- V11: 원본6 = 반영3+이미 존재1+제외1+보류1, 생성 거래2. 명시 이체 원본2→거래1, 외화 원문 보존/통화 합계 분리.
- V15: 파일/mapping/전환일/판정/기준 revision·장부 변경 뒤 토큰 거부, 준비/쓰기 중 취소·SQL 오류, commit 유실/재열기 조회·재반영0.
- V26: 논리 삭제·mapping/fence·원문/명령 결과의 백업/복원 왕복, 예전 백업 복원 뒤 자동 부활0.
- 실제 schema8 파일의 비파괴 Migration, 합성 자료만 쓰는 Room 파일/단위/화면·렌더·빌드/Lint·최신 CI로 검증한다. 원본 실제 자료와 기기 시험은 완료 근거에 포함하지 않는다.
