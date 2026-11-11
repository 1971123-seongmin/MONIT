# MONIT 모듈·DI·UI 개발 계획 초안

- 작성: Seongmin Kim의 요구사항을 바탕으로 Android 담당 정리
- 기준일: 2026-11-10 (Asia/Seoul)
- 상태: 설계 제안. 구현 승인 전이며 EGM 독립 검토는 아직 실행하지 않았다.
- MONIT 기준: PR #15 최신화 head `dff390cd06f6bf2631dae1956fa2c141eb0f331e`. 최초 구현 `4543440`과 제품·테스트·Gradle 내용은 같다.
- 이번 산출물: 계획 문서만 등록한다. Kotlin·Gradle·Room 스키마·Figma·원격 서비스 변경이나 구현 승인은 포함하지 않는다.

## 1. 해결할 문제와 현재 사실

MONIT의 장부 데이터·제출 의도·성공 영수증은 Room을 기준으로 관리한다. 모듈을 정리할 때에도 기존 데이터와 저장 보장을 유지한다.

현재 `core:model`에 금융 모델·검증 함수·Repository 계약이 함께 있다. `core:data`는 Room 구현과 Hilt 제공을 담당하고, PR #15의 계좌 ViewModel은 제출 캡처·직렬화·결과 검증·재시도·조회·UI 상태까지 담당한다. 앱은 Compose placeholder이며 실제 계좌 화면과 장부 context 진입 경로는 없다. Supabase 통신·인증·동기화도 아직 구현되지 않았다.

현재 코드의 사실 근거는 [저장 기반](../../core/data/README.md), [PR #15의 계좌 상태 계층](https://github.com/1971123-seongmin/MONIT/blob/dff390cd06f6bf2631dae1956fa2c141eb0f331e/feature/accounts/STATE.md), [현재 main 모듈](../../settings.gradle.kts), [공통 완료 기준](../ai/definition-of-done.md)다. 계좌 상태 계층은 PR #15 기준이며 이 문서 등록 MR의 제품 변경에 포함하지 않는다.

목표는 서버 DTO·Room Entity·도메인 모델·UI 모델의 소유자를 분명히 하고, ViewModel을 화면 상태 조정에 집중시키는 것이다. 기초 세팅과 데이터/상태 검증이 끝난 기능부터 제품 화면을 연결한다. 한 번에 전체 앱 구조를 갈아엎지 않는다.

## 2. 설계 원칙

| 구조 | 책임 |
| --- | --- |
| UI → ViewModel → Repository → 데이터 소스 | ViewModel이 data 계층의 공개 Repository 계약을 호출한다. Room DAO·Entity·SDK 구현체는 data 내부에 숨긴다. |
| 생성자 주입 / Binds / Provides 분리 | 일반 클래스 생성·인터페이스 연결·외부 객체 생성을 각각 구분한다. |
| DTO/Entity → 앱 모델 → UI 모델 | 각각 data와 feature 경계에 변환을 둔다. 별도 domain 모듈 없이도 모델을 구분하며 Entity·서버 응답을 화면에 넘기지 않는다. |
| Route / Screen, 상태·콜백 주입, Preview | Route가 수집·탐색을 연결하고 Screen은 합성 상태만으로 렌더 가능하게 한다. |
| 기능 컴포넌트와 공통 디자인 시스템 구분 | 업무 의미가 있는 UI는 feature에, 범용 시각 규격은 designsystem에 둔다. |
| 선택적 domain / UseCase | 기본 경로에 추가하지 않는다. 여러 ViewModel에서 재사용하거나 Repository 책임과 구분되는 복잡한 비즈니스 로직이 확인될 때만 도입한다. |
| 금융 결과와 화면 효과 구분 | 금융 저장 성공은 상태/영수증으로 유지하고, 알림·애니메이션은 UI에서 표현한다. |

## 3. 모듈 경계 제안과 선택지

다음은 권고 설계이며 아직 확정 구조가 아니다. 최종 선택은 독립 검토와 사용자 확인 후 ADR로 기록한다.

| 선택지 | 작동 방식 | 이점 | 한계·실패 조건 |
| --- | --- | --- | --- |
| A: UI·data 기본 계층 | `core:model`은 금융 모델·순수 검증 함수를 유지한다. `data:ledger`에 Repository 계약·구현과 데이터 소스를 두고 ViewModel이 공개 Repository를 호출한다. | 별도 domain 모듈과 위임용 UseCase 없이 기본 흐름을 명확히 한다. | ViewModel에서 복잡한 비즈니스 로직을 중복하기 시작하면 선택적 domain 분리가 필요하다. |
| B: 선택적 domain 계층 | 실제 복잡성/재사용 근거가 확인된 작업에만 UseCase를 둔다. UseCase는 data Repository에 의존하며 다른 작업은 계속 Repository를 직접 호출한다. | 필요한 로직만 공유·격리할 수 있다. | 사용 근거 없이 먼저 생성하면 호출 단계와 DI만 늘어난다. |
| C: 현재 모듈 유지 | `core:data` 이름과 경로를 유지하고 내부 책임만 정리한다. | 모듈 이동량이 가장 작다. | 기능별 데이터 영역이 늘어나면 범용 모듈에 여러 책임이 모일 수 있다. |

기본 설계는 A다. Google 권장 아키텍처의 UI·data 계층을 따르고 domain/UseCase는 필수로 두지 않는다. `core:data`를 `data:ledger`로 옮기는 것은 기능별 소유를 드러내기 위한 제안이며 UseCase 도입과 별개다. 아래 계획은 A를 기준으로 하고, 실제 파일 이동은 구조 승인 뒤 진행한다.

### 처음 정리할 모듈

| 모듈 | 책임과 공개 경계 |
| --- | --- |
| `app` | 앱 시작, 안정적인 장부 context 진입, 최상위 탐색, Hilt 최종 조립. 금융 계산·SQL·HTTP 로직은 두지 않는다. |
| `core:model` | Kotlin/JVM. 계좌·거래·미확인 잔액·장부 context·명령/결과 모델과 금액/날짜의 순수 검증. Android·Hilt·Room·SDK·HTTP 응답 코드에 의존하지 않는다. |
| `data:ledger` | 공개 Repository 계약, 내부 Room DB·DAO·Entity·Repository 구현·데이터 변환·관련 DI. 기존 원자성·중복/세대 검사·요청 bytes·영수증을 유지한다. |
| `core:designsystem` | 테마·컬러·타이포·간격·Shape·공통 시각 컴포넌트. Repository·ViewModel·업무 모델에 의존하지 않는다. |
| `feature:accounts` | 계좌 등록/선택의 Route·Screen·ViewModel·UiState·UiModel·UI mapper·기능 컴포넌트·Preview. |
| `feature:transactions` | 수입·지출·이체 입력의 같은 경계. 계좌 화면을 직접 호출하지 않고 탐색 콜백/반환 계약을 사용한다. |
| `feature:calendar` | 월·선택일·필터·목록·조회 상태. 다른 feature의 ViewModel을 공유하지 않는다. |

기본 의존 방향은 `app → feature / data`, `feature → data의 공개 Repository / core:model / designsystem`, `data → core:model`이다. feature에서 data의 internal 구현체·DAO·Entity는 참조하지 못하게 한다. domain이 필요해지면 해당 작업에만 `feature → domain → data`를 추가한다. Repository 계약을 domain으로 옮겨야 한다는 규칙은 두지 않는다. 기능끼리의 Gradle 의존과 순환 의존은 금지한다. 디자인 시스템에는 범용 문자열/상태만 전달하고 금융 모델 변환은 feature가 담당한다.

### 실제 사용이 생길 때 추가할 기반

- `core:network`: 공유 Supabase client·직렬화·연결 설정과 외부 객체 DI. 금융 DTO/업무 정책은 넣지 않는다. 별도 HTTP API가 확정된 경우에만 해당 client를 추가한다.
- `data:auth`: 인증 기능 착수 시 공개 인증 Repository와 SDK 어댑터를 둔다. UI에는 토큰 원문 대신 인증 상태·사용자 식별 정보를 제공한다. 별도 인증 UseCase/domain 모듈은 기본 생성하지 않는다.
- `core:database`: 실제로 여러 data 영역이 같은 DB를 공유해야 할 때 재검토한다. 현재 장부 DB 한 소비자만을 위해 Room 래퍼와 범용 DAO 계층을 추가하지 않는다.
- `core:navigation`: 여러 기능이 공유할 구체적 타입/헬퍼가 생긴 경우만 분리한다. 이름만 있는 marker interface부터 복제하지 않는다.
- 분석 SDK·WebView·공통 캐시·범용 security 모듈은 현재 첫 제품 동선의 필수 모듈이 아니다. 필요한 기능과 비용·보안 근거가 생기기 전 생성하지 않는다.

## 4. Room 데이터 경계

- 계좌·거래·잔액 기준·제출·영수증은 Room을 일관된 조회 원본으로 사용한다. UI는 Repository를 통해 접근한다.
- DB·DAO·Entity는 data 내부에 두고 도메인 모델로 변환해 반환한다.
- 장부 ID·storageGeneration은 Room 메타와 일관되게 복원하고 매 ViewModel/프로세스마다 재생성하지 않는다.
- 화면 saved-state와 영속 초안·제출 복구를 구분한다. 영속 초안이 필요하면 Room의 별도 계약으로 정한다.
- Preview·테스트 데이터는 합성 데이터로 주입하며 실제 장부에 자동 삽입하지 않는다.

인증정보 보관 방식은 인증 기능 설계에서 별도로 확정한다. 이번 계획은 인증 모듈의 계약과 DI 경계까지만 다루며, 모든 데이터를 Room에 저장한다고 정하지 않는다.

현재 context 조회/선택 API가 없으므로 앱 bootstrap은 선행 설계 항목이다. Backend가 빈 저장소의 원자적 첫 생성·단일 장부 재사용·손상/복수 장부의 복구 필요 결과를 논리 계약으로 정리했다. Android는 이를 공개 Repository API와 실제 원자적 조회/생성으로 설계해야 한다. 재시작 시 같은 context를 읽으며 임의의 첫 행 선택·자동 재생성은 하지 않는다. 새 영속 필드가 필요하면 승인된 Migration 및 기존 v1 보존 시험을 별도 단계로 둔다.

### 저장 파일 보호 검토안

현재 Room 구성에는 DB 암호화가 없으며, `allowBackup=false`는 DB 암호화를 대신하지 않는다. 앱 전용 저장 영역의 접근 제한과 저장 파일 암호화를 구분한다.

Android Keystore는 암호화 키를 보호하고 키를 사용한 암·복호화 연산을 제공한다. Room DB 전체를 자동 암호화하는 기능은 아니다. 금융 DB 파일의 기밀성까지 요구한다면 아래 조합을 설계 후보로 검토한다.

- DB 내용: Room과 호환되는 DB 암호화 구현이 담당한다. SQLCipher는 후보이며 아직 선정·의존성 추가하지 않았다.
- 키 보호: 별도로 생성한 무작위 DB 키를 Keystore 키로 암호화해 보관하고, DB를 열 때 복호화한다. Keystore 키 원문을 추출해 DB 암호로 전달하는 방식은 사용하지 않는다.
- 범위: 키 보호 모듈은 실제 사용처에 맞춰 작게 두며 Repository·UI에 키 원문을 노출하지 않는다. 하드웨어 보호 여부를 모든 기기에서 보장한다고 가정하지 않는다.
- 선행 결정: 기기 분실/DB 파일 유출 등 보호 대상, 앱 잠금·백그라운드 접근, 키 무효화·기기 이전·백업/복원 정책을 확정한다. 실행 중인 앱 프로세스가 침해된 경우의 완전한 보호를 주장하지 않는다.
- 검증: 현재 DB의 암호화 전환·실패 롤백, DB/WAL 파일의 보호, 잘못되거나 유실된 키로 열기 실패, 키 복원 불가 시 사용자 데이터 보존을 시험한다. 키 오류를 DB 삭제나 평문 fallback으로 처리하지 않는다.

암호화 구현·키 정책·라이브러리 호환성/이용 조건은 별도 보안 설계 승인 대상이다. 이번 문서 수정으로 보안 기능이 구현됐다는 뜻은 아니다.

## 5. Repository·서버 통신과 선택적 UseCase

### 로컬 장부

- `LedgerRepository`는 Entity나 raw 내부 requestJson을 공개 소비 형식으로 사용하지 않는다. typed 모델·제출 결과·도메인 오류를 제공한다.
- Room 생성·트랜잭션은 data 내부에 유지한다. 계좌+기준점, 이체 양쪽 효과, 금융 변경+영수증은 계속 한 번에 성공하거나 롤백된다. UseCase로 옮긴다는 이유로 여러 Repository 쓰기로 원자성을 분해하지 않는다.
- 입력값의 유효성·금융 날짜/금액 규칙은 순수 Kotlin으로 시험한다. 입력 문자열 보존·필드 오류 표시는 feature 책임이다.
- 조회에는 정합된 월/선택일 결과 계약을 마련한다. 독립 Flow 여러 개를 단순 combine해 서로 다른 revision의 계좌·거래·합계를 한 화면에 섞지 않는다.
- 현재 snapshot은 asOf를 서울 오늘까지로 제한한다. 미래 월 조회는 거래가 없다는 이유로 예외를 빈 결과로 바꾸지 않는다. 조회 API 확장 또는 지원된 asOf의 데이터와 미래 표시 기간을 분리하는 정확한 계약을 먼저 확정한다.

### Repository 책임과 선택적 로직 분리

- 계좌 생성·거래 생성은 캡처된 제출을 입력으로 받는다. UUID/값을 재시도마다 새로 생성하지 않는다.
- 저장·조회·영수증 검증·멱등 재시도처럼 데이터 일관성에 해당하는 규칙은 먼저 Repository 또는 data 내부 협력 객체가 맡는다. ViewModel은 이 계약의 typed 결과를 UiState로 변환한다. 단순한 CRUD마다 UseCase를 추가하지 않는다.
- 여러 ViewModel이 같은 복잡한 비즈니스 흐름을 중복하거나 Repository와 구분되는 로직이 비대해졌을 때만 UseCase를 제안한다. 대상 로직·소비자·테스트 이점을 먼저 기록하고, 이름만 바꾼 위임 클래스는 만들지 않는다.
- 선택적으로 도입한 UseCase는 data Repository에 의존하며 화면별 mutable 상태·진행 중 Job·draft를 보관하지 않는다. UI 진행 상태는 ViewModel, 영속 제출은 Repository가 소유한다.
- 복구할 제출의 종류·context·요청 값을 안전하게 식별하는 typed 계약을 마련한다. feature에서 opaque pending JSON을 해석하거나 다른 종류의 제출을 무조건 retry하지 않는다.
- 등록된 계좌/거래를 UI 모델로 바꾸는 mapper는 feature의 model 파일로 옮긴다. 검증된 저장 결과와 실제 조회 실패는 계속 구분한다.

### 서버 경계

기본 연결은 `ViewModel → data Repository → remote 어댑터 → Supabase client`다. 서버 DTO는 data 내부에서만 사용하고 명시적으로 앱의 금융 모델로 변환한다. `core:network`에 업무 DTO를 모으지 않는다. HTTP status·nullable 응답 본문·SDK exception을 모델 필드로 흘려보내지 않는다.

서버 미확정 부분은 합성 응답을 사용하는 개발/테스트용 어댑터로 준비할 수 있다. 이 어댑터는 별도 개발 variant/명시적 DI binding에서만 사용하고 release에서 선택되지 않음을 확인한다. 서버 장애·설정 누락·인증 실패 시 자동 목 성공 fallback은 금지한다.

서버 통신 기반은 제품 화면보다 먼저 준비하되 실제 금융 동기화는 계약 승인 전 시작하지 않는다. 사용자 소유권·RLS, 멱등키 범위, 서버 revision, 재시도/확정 결과 조회, 충돌 해결, 오프라인 변경, 로그아웃/계정 변경 시 로컬 데이터 정책이 선행 조건이다. SDK 로그인 성공을 장부 동기화 성공으로 표시하지 않는다. 원격 적용·RLS 변경·새 과금은 별도 승인 대상이다.

## 6. DI 구성

| 위치/방법 | 제공할 것과 수명 |
| --- | --- |
| `@Inject constructor` | Repository 구현·실제 책임이 있는 data source/adapter 등 의존성을 받을 수 있는 일반 클래스. 단순 DAO 전달만 하는 래퍼는 자동 생성하지 않는다. |
| `data:ledger/di/RepositoryModule`의 `@Binds` | `LedgerRepository`와 장부 context 계약을 구현체에 연결한다. 공유가 필요한 Repository는 Singleton으로 둔다. |
| `data:ledger/di/DatabaseModule`의 `@Provides` | ApplicationContext 기반 기존 이름의 Room DB. 앱 수명에 하나이며 destructive fallback을 사용하지 않는다. |
| `ClockModule`의 `@Provides` | 현재 서울 Clock과 테스트 대체 지점. 의존 영역에서 제공하고 테스트는 고정 Clock을 사용한다. |
| `core:network`의 `@Provides` | 서버 기반 착수 시 검증된 Supabase client와 직렬화 설정. client를 요청마다 만들지 않는다. |
| `@HiltViewModel` | ViewModel 생명주기별 상태. Activity·Composable이 concrete Repository를 생성하지 않는다. |
| Qualifier | 동일 타입의 실제 복수 구현이 있을 때만 도입한다. 단일 DB/Clock에 장식용 이름을 붙이지 않는다. 테스트/개발 mock은 환경별 binding으로 분리한다. |

기본 구성에서는 Hilt ViewModel에 Repository 계약을 직접 주입한다. UseCase 모듈이나 그 전용 DI 제공 모듈은 미리 만들지 않는다. 선택적 domain이 필요해질 때 그 작업의 생성자/DI를 함께 정한다. SDK·Room 생성은 `@Provides`, 인터페이스 연결은 `@Binds`로 구분하되, 문법을 맞추기 위한 모듈/클래스 추가는 하지 않는다. DI 모듈을 쪼개는 목적은 생성 책임과 교체 경계를 분리하는 것이다.

검증에는 정적 앱 그래프 컴파일과 실제 Hilt Route 생성/실행을 모두 포함한다. 컴파일 성공만으로 런타임 진입을 완료 처리하지 않는다. 테스트용 binding은 전역 production binding을 조용히 덮어쓰지 않도록 격리한다.

## 7. ViewModel·상태·복구

- ViewModel은 입력 Action을 받고 작업을 호출하며 불변 등록/화면 상태를 갱신한다. 순수 표시용 UiState와 작업 진행 상태의 명명·파일 구분은 [Android 지침](../ai/roles/android.md)을 따른다. StateFlow는 읽기 전용으로 공개하며 Main에서의 이벤트 직렬화와 늦은 응답 배제를 유지한다.
- `SaveState`와 `ReadState`를 분리한다. 저장 성공 뒤 목록 조회 실패가 금융 재저장을 유발하지 않는다. 상태별 필수 데이터는 해당 상태가 소유하도록 해 `Saved`인데 식별자가 없는 조합을 줄인다.
- 금융 성공·미확정 제출·충돌은 작업 상태/영속 결과로 표현한다. Snackbar·애니메이션 같은 보조 표현이 유실되더라도 금융 결과가 사라지지 않아야 한다.
- 초기 계좌 입력은 이름·유형·기준일·미확인 사유를 유지한다. 금액 확인 API가 없는 동안 기준액 확인·수정·삭제를 성공 가능한 행동으로 노출하지 않는다.
- `SubmittedAccount` 스냅샷과 codec을 ViewModel 밖의 별도 타입/파일로 분리한다. 버전·필드 이름·복원 검증을 명시하고 인덱스 ArrayList의 변경 영향을 격리한다. 과거 saved-state 버전의 처리도 정한다.
- `SavedStateHandle`은 화면 인자·가벼운 UI 상태의 복원 수단이다. 강제 종료/재부팅 후의 제출 복구는 Room의 typed pending 조회와 확정 결과가 근거이며 전체 draft 복구와 별도로 시험한다.
- 취소 예외는 전파한다. 실패를 빈 결과·0원·성공으로 바꾸지 않는다. 세대 변경·다른 제출 결과는 자동 덮어쓰기 없이 명시적 재진입 경로로 보낸다.
- 거래 입력은 종류·원문 금액·실제일·입출금 계좌·메모를 관리한다. 이체는 서로 다른 활성 계좌를 요구한다. 계좌 등록 후 돌아와도 입력과 반대편 계좌를 유지하고 새 계좌는 사용자가 명시적으로 선택한다.
- 달력은 월·선택일·필터·표시 방식을 관리한다. 월말 clamp·윤년·연도 경계, 미래 조회/입력 금지, 이전 월 응답 배제를 처리한다. 월 전체 요약과 필터 결과의 범위를 구분한다.

## 8. UI·컴포넌트·디자인 시스템

각 feature는 `Route`, `Screen`, `ViewModel`, `UiState/Action`, `model/mapper`, `component`, `preview`, `navigation` 책임을 구분한다. 빈 패키지나 각 한 줄을 감싼 기반 클래스를 먼저 만들지는 않는다.

- Route: Hilt ViewModel 생성, `collectAsStateWithLifecycle`, 탐색/반환 계약, 화면 효과 연결.
- Screen: UiState와 콜백만 받아 렌더한다. 실제 DB·SDK·Hilt 없이 Preview/Compose UI 테스트에서 실행 가능해야 한다.
- Component: 상태 끌어올리기와 의미 있는 콜백을 사용한다. 업무 데이터 모델은 UI 모델로 바꾼 후 전달한다.
- Navigation: 앱이 최상위 back stack을 소유한다. 기능 간 화면 인자는 작고 타입이 분명하게 정의한다. `toRoute()`는 진입 인자 복원에 사용하며 금융 제출 영속 복구를 대신하지 않는다.

저장 성공에 따른 화면 이동은 현재 destination과 operationId를 근거로 중복 소비를 막는다. 화면 효과의 소비 확인이 영수증/미확정 제출을 삭제하는 동작이 되지 않게 한다. 계좌 등록 성공 후 계좌 선택 화면에 초점을 주는 것과 거래의 계좌로 선택하는 것은 별개이며, 실제 선택은 사용자가 명시한다.

현재 MonitTheme·버튼·금액 입력·계좌 선택·거래 행·날짜 셀을 재사용한다. 계좌/거래 복합 UI는 feature에 두고 범용 재사용 근거가 생긴 부분만 designsystem으로 올린다. 서버 DTO·Repository·업무 ViewModel을 designsystem에 주입하지 않는다.

컬러·타이포·간격·Shape·상태 표현을 디자인 시스템에서 관리한다. 확정된 파랑·시스템 Light/Dark 추종·동적 색상 끄기를 유지한다. SEED·Material 3의 의미 토큰·접근성·컴포넌트 원칙을 참고하되 제품 자산과 값을 무조건 복제하지 않는다. Figma 디자인 시스템/와이어프레임/기능 화면 개편의 새 노드·변경 목록과 대조한 뒤 시각 구현을 확정한다. 문서만 작성된 상태를 Figma 또는 앱 구현 완료로 보고하지 않는다.

첫 화면 순서는 계좌 등록/선택/복귀 → 수입·지출·이체 입력 → 월간 달력/선택일 목록이다. 상태·저장 경계와 공통 컴포넌트 검증이 준비된 기능부터 화면을 연결한다. 디자인 미확정 부분 때문에 관련 없는 데이터 작업 전체를 중단하지 않는다.

모든 공통 컴포넌트와 구현된 제품 Screen에는 발견 가능한 top-level Preview를 둔다. 정상·빈·오류·로딩/저장 중·비활성 등 해당 상태를 합성 데이터로 재현한다. 대표 Light/Dark·320dp·200% 조합을 고르고 모든 조합의 무조건적인 곱집합은 만들지 않는다. Prevue는 QA의 지원 환경 검증을 재사용하고 실제 렌더 결과/차단을 기록한다. 도구 미검증을 렌더 성공으로 표시하지 않는다. Prevue는 Compose UI·접근성·실기기 시험을 대신하지 않는다.

## 9. Detailed Implementation Plan

다음은 이후 구현 승인 시의 작업 순서다. 아래 구현 단계별 Issue/PR은 아직 생성하지 않았으며, 숫자는 작업 단계다. 각 단계 종료 시 빌드 가능한 상태와 검증 근거를 남긴다.

| 단계 | 구체적 작업과 영향 경로 | 완료 기준 / 다음 단계 조건 |
| --- | --- | --- |
| P0 · 경계 확정 | 본 계획 검토, 모듈 선택 A/B/C와 context·복구·서버 계약의 열린 결정을 ADR로 확정. PR #15의 기존 동작·데이터 보장 목록 고정. | 사용자가 구조/범위 승인. 새 구현 코드는 아직 작성하지 않는다. |
| P1 · 모델/데이터 경계 | `core:model`의 금융 모델·검증 함수를 유지한다. `LedgerModels.kt`의 Repository 인터페이스는 `data/ledger/.../repository/LedgerRepository.kt`로 분리하고 `core/data` 구현/시험/schema 파일을 `data/ledger`로 이동한다. `settings.gradle.kts`, 관련 `build.gradle.kts`, feature import 갱신. | ViewModel → 공개 Repository 직접 호출. 한 PR 안에서는 경계/경로만 변경하고 DB 파일명·테이블·schema version·요청/해시 형식·알고리즘은 보존한다. 기존 시험/실제 DB 재열기 통과, 빈 옛 모듈·재export 래퍼 없음. |
| P2 · DI 분리 | `data/ledger/.../di/DatabaseModule.kt`, `RepositoryModule.kt`, `ClockModule.kt`와 Repository 생성자 조립을 정리. 기존 `LedgerDataModule`의 제공을 중복 등록하지 않는다. | Hilt 중복/누락 binding 없음, 파일 DB Singleton 유지, 앱 그래프/테스트 대체/실제 생성 경로 검증. |
| P3 · context·복구 계약 | Backend 최소 논리 계약을 data의 현재 장부 조회/선택·원자적 첫 생성·typed pending 공개 API로 설계한 뒤 별도 구현한다. 앱 시작 ViewModel 연결은 API 검증 후 진행한다. | 재시작/동시 진입에서도 같은 context, 손상/복수 장부/세대 불일치 명시 처리. schema 변경 시 승인된 Migration과 v1 데이터 보존 시험. |
| P4 · 제출과 계좌 상태 | Repository/data 내부의 제출·결과 확인 계약과 `feature/accounts/.../model/AccountUiModel.kt`, mapper, submitted state/codec, ViewModel 상태 조정을 정리한다. UseCase 분리는 실제 복잡성/재사용 근거가 있을 때만 별도 제안한다. | 기존 계좌 상태 18개 시나리오 유지 및 경계 이동 시험. 응답 유실/취소/재진입에서 ID·내용 보존, 금융 성공/조회 실패 구분. |
| P5 · 서버 기반 | 승인된 `core/network` client DI, `data/auth` 세션 어댑터, `data/ledger/.../remote` DTO/mapper/어댑터. 미확정 응답은 개발/테스트 DI의 합성 어댑터로 격리. | 실제/개발 binding 구분, release mock 미포함, 인증 상태·매핑·취소·서버 오류 시험. 실동기화 전 소유권/RLS/충돌 정책 별도 승인. 서버 미확정 경계를 문서화하면 로컬 제품 단계는 계속 가능. |
| P6 · 거래/조회 상태 | `feature/transactions` 입력·검증·제출 상태, `feature/calendar` 조회·선택·필터 상태, Repository 조회 계약과 data의 일관된 결과 구현. | 금액/날짜·이체·중복/별개 동일값·미래 조회·월말·늦은 응답·월 전체/필터 범위 시험. |
| P7 · 토큰/공통 UI 정합성 | `core/designsystem` 기존 토큰/5종 컴포넌트를 Design 새 명세와 대조. 필요한 기본/복합 상태와 Preview 목록 보완. | 토큰 임의 중복 없음, Light/Dark·오류·비활성·저장 중·좁은 폭/큰 글자·semantics 검증. 새 토큰/Figma 변경은 해당 승인 절차 적용. |
| P8 · 제품 화면 연결 | 각 feature의 `...Route.kt`, `...Screen.kt`, 기능별 컴포넌트/Preview/탐색 연결. 앞 단계에서 준비된 기능 순서대로 적용. | 계좌 생성 후 명시 선택/입력 보존 → 신규 거래 확정 → 달력·목록·요약 일관 갱신. 미지원 수정/삭제/기준액 확인은 동작 가능한 것처럼 노출하지 않는다. |
| P9 · 통합 QA | Compose UI·Prevue 렌더·QA sm-948n의 테마/320dp/200%/TalkBack/실제 종료·복귀 확인. 최신 head CI와 필요한 독립 리뷰 확인. | AC별 실제 근거, 미지원/실패/미검증 구분. 강제 종료·재부팅·영속 초안은 실제 지원 계약과 시험이 있는 범위만 완료 처리. |

P1~P4까지는 금융 동작 보존이 우선이다. P5의 서버/인증 작업과 P7의 시각 명세는 각 담당과 조율하며 진행하되, P8 제품 화면은 해당 데이터/상태·디자인 시스템 준비 뒤에 연결한다. 별도 금융 동기화 완성을 모든 로컬 화면의 선행 조건으로 만들지는 않는다.

향후 Kotlin 변경 전에는 각 단계의 정확한 생성/수정/이동 파일과 테스트를 해당 Issue에 고정한다. 위 표의 미래 모듈 경로는 변경 허용 목록이 아니다. 외부 계약이 열린 P3/P5는 그 결정 없이 구현 준비 완료로 판단하지 않는다.

## 10. 검증 기준

| 영역 | 필요한 관찰 결과 |
| --- | --- |
| 구조 | feature는 공개 Repository만 참조하고 internal 구현체·DAO·Entity에는 접근하지 않음. 순환/빈 모듈/중복 DI와 위임용 UseCase 없음. 선택적 domain은 재사용·복잡성 근거가 있을 때만 생성. |
| 보존 | 기존 DB/요청 bytes/해시/영수증 재열기. 실제 파일 DB의 실패 롤백·이체 원자성·context 격리 유지. |
| 제출 | 중복 탭 하나, 재시도 같은 ID/값, 별개 동일값 새 ID, 응답 유실 결과 확인, 조회 실패 재저장 없음, 취소 전파. |
| 복구 | 시스템 화면 재생성·태스크 제거·강제 종료·재부팅을 구분. typed pending과 영수증으로 복구하고 전체 입력 초안 지원을 별도 판정. |
| 서버/인증 | 인증 상태 초기 로드/성공/실패/갱신/로그아웃/계정 전환, RLS 격리, 매핑/범위 검증, mock과 실제 binding 구분. 원격 호출은 승인된 환경에서만 시험. |
| UI | 오류와 빈 결과 구분, 입력 유지, 저장 중 차단, 새 계좌 명시 선택, 이전 응답 배제, 실제 저장 결과와 화면 복귀 일치. |
| 접근성 | 모든 상호작용 최소 48dp, 라벨/오류/선택 상태 의미, 키보드/IME/붙여넣기, 큰 글자 잘림 없음, TalkBack 초점/읽기 순서. |
| 비용/운영 | 새 과금 서비스 도입 없음, 금융값/토큰 로그 없음. 필요한 최소 모듈·명시적 의존·선택한 대표 Preview만 추가. |

현재 기준 검증은 모델 18·Room 18·디자인 시스템 13·계좌 상태 18개다. 이 숫자는 이후 설계의 통과 결과가 아니며 실제 UI/서버/기기 연결을 보증하지 않는다. 문서만 작성하는 이번 작업에는 Gradle·기기 검사를 새로 실행하지 않는다.

## 11. 열린 결정·반대 의견·중단 조건

| 결정/위험 | 판단에 필요한 것 | 확정 책임 / 보류 시 영향 |
| --- | --- | --- |
| 모듈 경계 | UI·data 기본 구조를 유지하면서 `core:data`를 기능별 `data:ledger`로 옮길 범위 | 사용자 구조 승인. 미확정이면 코드 이동하지 않는다. domain/UseCase는 기본 경로에서 제외. |
| DB 파일 암호화·키 보호 | 보호 대상, Room 암호화 구현 후보·키 수명·백업/복원·기존 DB 전환 | 사용자 보안 설계 승인. Keystore 조합은 검토안이며 구현/라이브러리 선정은 하지 않는다. |
| context bootstrap | 단일 장부 재사용·원자적 첫 생성·손상/복수 장부 차단의 논리 계약은 수령. 공개 API·현재 장부 선택과 v1 보존 시험은 미구현 | Android 상세 설계·별도 구현. 실제 화면 진입의 선행 조건. |
| 요청 크기 P2 | Backend 보완 완료: 입력별 16 KiB와 캡처 원문을 구분. 현재 Room 경로의 경계/원문/hash 재시도 시험 통과 | 현재 저장 경로의 Backend 대기 해제. 전체 백업 JSON 8 MiB·writer/reader round-trip은 백업 구현 단계에서 검증. |
| typed pending·영속 초안 | context 확인·결과 우선 조회·typed 선택·명시 재시도 논리 계약 수령. 공개 typed API와 saved-state 소실 복구는 미구현 | Android API 설계·별도 구현. 영속 입력 초안은 별도 정책이며 현재 복구 완료 표시 금지. |
| 인증/계정 전환 경계 | 인증 상태·갱신·로컬 장부 소유/로그아웃 정책 | Backend/Android와 필요한 사용자 정책 승인. 인증/동기화 활성화 보류. |
| 서버 동기화 | RLS·버전·소유권·동일 제출·충돌/오프라인 정책 | Backend 및 사용자 승인. 개발 mock으로 상태/UI는 진행 가능. |
| Figma 개편 | 새 노드/토큰/상태·컴포넌트 매핑 | Design. 시각 구현과 승인만 해당 부분 대기. |
| 추상화 증가 | 같은 책임을 중복 구현하는 실제 두 소비자가 있는지 | Android. 역할 없는 UseCase/DataSource/core 모듈은 생성하지 않는다. |

Room 유지에는 오프라인 동작과 이미 검증한 원자성을 보존하는 이점이 있지만 향후 서버와의 동기화 비용이 있다. 서버 직접 조회만으로 단순화하는 대안은 지금의 오프라인 제출/복구 보장을 다시 설계해야 하므로 이번 권고에서 채택하지 않는다. 이 판단은 사용자가 이미 확정한 결정이라고 주장하지 않는다.

### 장부 진입·typed pending 복구의 구현 준비

Backend 작업본 `docs/architecture/local-ledger-contract.md` 8.1절과 `docs/contracts/README.md`의 P2를 2026-11-11 조회했다. 현재 main `5502ba7`에는 두 문서가 등록되지 않았으므로 계약 수령과 Git 원본 등록을 구분한다. Backend 문서 원본은 담당자가 등록하며 이 PR에 복사하거나 수정하지 않는다. 아래는 Android 후속 설계 범위이며 새 API 명칭·SQL·schema를 확정하거나 구현한 결과가 아니다.

1. **장부 진입 API를 먼저 설계한다.** 저장소 확인·검증된 최초 빈 상태·ID 쌍 생성·메타 저장을 하나의 원자적 경계로 묶는다. 유효한 단일 장부는 기존 context를 반환한다. 조회 실패·메타 손상·복수 장부는 신규 생성 없이 복구 필요로 구분한다. 화면은 UUID를 생성하지 않는다.
2. **typed pending 조회 경계를 설계한다.** data가 원문 hash·버전·context·종류를 검증하고 계좌 등록의 operationId·대상 계좌/기준점·typed 입력을 반환한다. 손상·미지원 종류·조회 실패를 빈 목록으로 대체하지 않는다. UI는 내부 JSON을 해석하지 않는다.
3. **복귀·재시도 규칙을 연결한다.** 결과를 먼저 조회하고 선택한 기존 ID만 명시적으로 재시도한다. pending이 없어도 이미 commit됐을 수 있으므로 기존 계좌 목록으로 복귀한다. 기존 결과가 불명확할 때 새 ID를 자동 발급하거나 다른 종류의 pending을 일괄 실행하지 않는다.
4. **별도 구현의 수용 기준을 고정한다.** 첫 진입 경합·재열기 시 같은 context, 손상/복수 장부 보존, 세대 불일치, typed 검증 실패, 선택 중 다른 실행의 선행 commit, pending 없는 확정 결과를 시험한다. 필요 시 승인된 Migration을 포함하며 현재 PR에 새 금융 기능을 추가하지 않는다.

바로 준비할 후속 작업은 **장부 진입·typed pending 공개 Repository API의 상세 설계와 별도 이슈 범위 정리**다. 논리 계약은 수령했지만 구현·제품 화면 연결 준비 완료는 아니다. 백업 writer/reader 검증·전체 입력 초안·디자인 시스템 신규 구현은 이 작업과 분리한다.

## 12. EGM 독립 검토 제안

- 대상: 이 파일 전체. 초기 계획의 구조·실제 코드와의 일치·구현 선행 조건을 검토한다.
- 검토자: `egm-design`(모듈 경계·과설계), `egm-codebase-details`(현재 코드와의 차이·데이터 보존), `egm-implementation-readiness`(UI/DI/복구 순서·열린 계약).
- 기준: design은 feature-spec-standard, 나머지는 implementation-readiness-standard. 열린 결정은 구현 준비의 제약으로 명시적으로 판정한다.
- 읽기 목록: 위에 연결한 현재 코드/계약, `core/model/.../LedgerModels.kt`, `core/data/.../LedgerDataModule.kt`, `RoomLedgerRepository.kt`, 계좌 ViewModel·상태·테스트, 디자인 시스템 README와 실제 theme/component 파일. 공식 문서의 추가 확인이 필요하면 범위를 표시한다.
- 실행: 사용자 확인 후 부모 대화 없이 fresh context의 읽기 전용 검토자 3명을 병렬 실행한다. 대화 격리가 파일시스템/AGENTS까지 격리했다는 뜻은 아니다.
- 결과: 파일/행·심각도·근거가 있는 지적을 문서로 모은다. 문서 수정 제안도 확인 단계 뒤 반영하며, Kotlin·Gradle·schema·외부 서비스는 변경하지 않는다.
- 검토 라운드 재실행과 문서 수용은 EGM checkpoint를 따른다. 아직 실행하지 않았으므로 검토 통과·구현 준비 완료·사용자 승인으로 표시하지 않는다.

## 13. 참고 자료

- Android 공식 data/domain/Hilt/UI events/offline-first 문서를 판단 근거로 사용했다. Google 권장 아키텍처에서도 domain/UseCase는 선택적이며 복잡한 로직 또는 여러 ViewModel의 재사용 필요가 있을 때만 도입한다. UseCase라는 이름이 특정 아키텍처 전용이라는 뜻은 아니다.
- Supabase 공식 Kotlin 초기화·세션 상태/갱신 문서는 인증 모듈 경계를 설계할 때 확인한다. SDK 도입 버전과 실제 API는 착수 단계에서 고정·검증한다.

원문 링크: [Android data](https://developer.android.com/topic/architecture/data-layer), [Android domain](https://developer.android.com/topic/architecture/domain-layer), [Hilt](https://developer.android.com/training/dependency-injection/hilt-android), [UI events](https://developer.android.com/topic/architecture/ui-layer/events), [offline-first](https://developer.android.com/topic/architecture/data-layer/offline-first), [Supabase Kotlin 초기화](https://supabase.com/docs/reference/kotlin/initializing), [세션 상태](https://supabase.com/docs/reference/kotlin/v2/auth-onauthstatechange), [세션 갱신](https://supabase.com/docs/reference/kotlin/auth-refreshsession).

보안 검토 근거: [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [앱 데이터 저장](https://developer.android.com/training/data-storage), [Room](https://developer.android.com/training/data-storage/room), [Room 호환 DB 암호화 후보](https://github.com/sqlcipher/sqlcipher-android).
