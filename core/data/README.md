# 로컬 저장 기반

`LedgerRepository`의 첫 구현이다. `app → core:data → core:model` 의존 방향을 유지하며 Room Entity와 DAO는 모듈 내부에 둔다. `di`의 `LedgerDatabaseModule`·`LedgerClockModule`은 파일 DB와 서울 Clock을 Singleton으로 제공하고, `LedgerRepositoryModule`은 생성자 주입 가능한 Room 구현을 Repository 계약에 Singleton으로 바인딩한다. 실제 제품 화면은 아직 연결하지 않았다.

공통 실행 의존성은 [core:common](../common/README.md)에서 제공한다. Repository는 `CoroutineContextProvider`를 생성자로 주입받고 `default`로 요청 직렬화·해시·복원 준비를 실행한다. Room 트랜잭션 경계와 호출자 취소는 유지한다. Android Context는 DB 제공부에서 Hilt의 `@ApplicationContext`로 주입하며 Repository나 전역 객체에 보관하지 않는다.

## 지원 경로

1. `openLedger()`가 기존 단일 장부의 context를 반환한다. 모든 업무 기록까지 비어 있는 최초 저장소에서만 같은 트랜잭션 안에서 UUID 쌍과 revision 1 메타를 생성한다. 메타 누락·손상·복수 장부는 `RecoveryRequired`이며 기존 행을 변경하지 않는다. 조회·쓰기 오류와 취소는 전파한다. 기존 `initialize(context)`도 유지한다. 자동 계좌·0원 기준·샘플 거래를 만들지 않는다.
2. 계좌 생성은 계좌와 미확인 기준 상태를 함께 저장한다. 기준일은 서울 오늘 이전, 1900-01-01 이후이며 미확인 금액·확인 시각은 null이다.
3. 수입·지출·내부 이체를 생성한다. 실제일은 서울 오늘까지, 금액은 1..999,999,999,999원이다. 참조 계좌는 같은 가계부의 활성 계좌여야 한다. 이체는 두 계좌를 참조하는 한 행이다.
4. `snapshot(context, asOf)`는 같은 DB 트랜잭션에서 목록·잔액·선택 월 수입/지출을 계산한다. 기준일 당일 거래는 잔액에 재가산하지 않는다. 미확인 기준의 잔액은 null, 내부 이체는 수입/지출 합계에서 제외한다. 넓은 정수로 합산한 최종 결과의 범위를 검사한다.

모든 ID는 소문자 정규 UUID다. 이름·메모 등 업무 문자열은 유효한 Unicode와 UTF-8 16 KiB 상한을 검사한다. 이름이 같은 계좌도 별도 ID로 구분한다.

## 제출과 복구

저장 의도마다 호출자가 `operationId`를 한 번 생성하고, 응답 유실이나 실패 후 재시도에는 같은 ID와 값을 유지한다. 다른 내용을 제출할 때 기존 ID를 재사용하지 않는다.

- `submissions`에 요청을 먼저 확정한 뒤, 다음 단일 트랜잭션에서 금융 행·ledger revision·`operations` 성공 결과·제출 의도 소비를 확정한다. 실패하면 금융 변경은 롤백하고 제출 의도는 보존한다.
- `(ledgerId, operationId)`의 같은 요청은 원래 receipt를 반환한다. 새 명령으로 간주하거나 revision을 다시 올리지 않는다. 다른 요청은 `DUPLICATE_REQUEST` 충돌이다.
- `receipt`로 성공 결과를 조회하고 `pendingWrites`·`retryPending`으로 캡처한 미확정 요청을 복구한다. 제출 의도는 성공 결과도, 입력 폼의 전체 초안도 아니다. API 예외만으로 성공 여부를 추정하지 않는다.
- UI 소비자는 `pendingRecovery(context)`로 검증된 계좌 등록 값과 행의 `operationId`를 조회한다. context 확인과 제출 조회는 같은 트랜잭션이며 쓰기·소비·실행 예약은 하지 않는다. 원문/hash는 공개 결과에 노출하지 않는다. 알 수 없는 version/kind와 현재 미지원인 거래 종류는 미지원 항목, 잘못된 UUID/hash/context와 지원 형식의 비정규 원문은 손상 항목으로 남긴다. ID 자체가 잘못되면 재시도 키는 null이다. 정상 조회에 항목이 없을 때만 빈 목록을 반환한다. 조회 후 소비·세대 변경은 기존 `receipt`·`retryPending`에서 다시 확인해야 한다.
- 호출 context가 없거나 세대가 다르면 기존 `LedgerConflictException`으로 조회 전체를 거부한다. 항목의 `CONTEXT_MISMATCH`는 조회 context와 캡처 원문이 다른 경우다. 행 ID → hash → version → context → kind → 지원 payload 순으로 검사하며 미지원 항목도 행 ID와 hash는 검증한다. 검증된 항목도 실행 가능성을 보장하지 않으므로 선택 후 `receipt`로 결과를 확인하고, 결과가 없을 때 사용자가 재시도를 선택하면 같은 ID로 `retryPending`한다. 해당 API들이 최신 상태를 다시 확인한다.
- context의 세대가 다르면 거부한다. ledger revision의 조건부 증가가 0행이면 금융 변경 전체를 롤백한다.

내부 요청 v1은 `version`, `ledgerId`, `expectedStorageGeneration`, `kind`, `targetId`, `expectedRevision`, `value`를 고정 순서로 캡처한다. 신규 명령의 `expectedRevision`은 null이며 종류는 `UPDATE_ACCOUNT` 또는 `CREATE_TRANSACTION`이다. 금액은 정수 문자열로 쓰고, 정확한 요청 UTF-8 bytes의 SHA-256을 보존한다. 이 형식은 서버 DTO·백업 포맷이 아니다. 성공 결과의 ledger revision과 참조 객체의 revision은 서로 구분한다.

`operationId`는 submissions 행에서 가져오며 v1 원문에 추가하지 않는다. 입력별 UTF-8 16 KiB 한도와 이스케이프를 포함한 캡처 원문 크기는 별개다. 복구 조회에 직렬화 원문 16 KiB 제한이나 단일 요청 8 MiB 제한을 추가하지 않는다. 전체 백업의 크기 검증은 후속 구현 범위이며 이 조회와 별개다.

## 스키마와 검증 경계

schema v1의 테이블은 `ledgers`, `accounts`, `anchors`, `transactions`, `operations`, `submissions`다. 복합 키·같은 가계부 FK·ABORT 삽입으로 참조와 기존 행을 보존하며 REPLACE·연쇄 삭제·destructive migration을 사용하지 않는다. 계좌당 현재 기준 상태 하나를 유지한다. 이후 스키마 변경 시 실제 버전 증가와 보존 Migration 테스트를 추가한다.

파일 DB는 WAL과 `synchronous=FULL`을 설정한다. Repository의 각 쓰기 트랜잭션에서 현재 writer 설정을 확인하고 불일치하면 거부한다. 이 설정 확인과 호스트 재열기 시험이 실제 기기의 강제 종료·재부팅·전원 손실 시험을 대신하지 않는다.

```powershell
.\gradlew.bat :core:model:test :core:data:testDebugUnitTest :app:assembleDebug :app:lintDebug :core:data:lintDebug
```

Robolectric 테스트는 실제 Room 생성 DAO와 파일 SQLite로 중복·참조 격리·실패 롤백·재열기 복구·동시 요청을 확인한다. 취소 시험은 Room 트랜잭션의 삽입 후 commit 전 롤백을 검사한다. 확인된 기준액·보관 상태·세대 전환은 일부 시험의 SQL fixture이며 제품 변경 API의 구현을 뜻하지 않는다.

`LedgerRecoveryTest`는 최초 진입 경합·재열기·메타 모순·각 업무 테이블의 고아 행·삽입 실패·대기 중 취소와 typed 조회의 원문 보존·손상/미지원 분류·오류 전파를 검사한다. 화면 연결·복원 UI·실기기 시험은 포함하지 않는다.

기준액 확인/변경, 편집·삭제·이력, 납입 연결, 입력 폼 초안, 이관·백업·복원, 서버 동기화와 Flow 기반 UI 조회는 후속 범위다. 소비자는 `LedgerRepository`를 사용하고 DAO나 DB 파일을 직접 조작하지 않는다. 현재 전체 가계부를 읽는 계산은 초기 구현이며 데이터 규모에 따른 성능 검증은 별도로 필요하다.
