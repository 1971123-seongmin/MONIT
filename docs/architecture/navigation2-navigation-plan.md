# Navigation 2 화면 이동 설계안

- 상태: 검토용 제안. 제품 코드·모듈·의존성 추가와 구현 승인을 포함하지 않는다.
- MONIT 기준: main `6ea87dac8eaf4ec25fa8269a36b03df6f9246bca`. 관련 작업은 [#28](https://github.com/1971123-seongmin/MONIT/issues/28)이다.
- 목적: 기존 3탭과 거래 입력·계좌 선택/등록의 이동·복귀·복원 책임을 정한다. #24 수정, 납입 API, 새 딥링크, Navigation 3, SDK 출판은 별도 범위다.

## 1. 조사 기준과 현재 구현

참고 기준은 [전환 PR #1902의 커밋 목록](https://github.com/android/nowinandroid/pull/1902/commits)에서 확인한 base **`d438a007c4ba5794522d963d8c53732dfb5be6f8`**이다. PR의 base SHA와 첫 커밋 `068d8cc9`의 부모가 일치한다. 마지막 커밋 `ecb759d8`의 바로 이전 커밋도 전환 작업에 포함되므로 그 버전을 Navigation 2 기준으로 삼지 않았다.

| 실제 확인한 고정 원본 | 확인한 방식 / MONIT에 적용할 범위 |
| --- | --- |
| [NiaNavHost](https://github.com/android/nowinandroid/blob/d438a007c4ba5794522d963d8c53732dfb5be6f8/app/src/main/kotlin/com/google/samples/apps/nowinandroid/navigation/NiaNavHost.kt) | 앱의 NavHost에서 feature 진입 함수와 탐색 콜백을 조립한다. |
| [NiaAppState](https://github.com/android/nowinandroid/blob/d438a007c4ba5794522d963d8c53732dfb5be6f8/app/src/main/kotlin/com/google/samples/apps/nowinandroid/ui/NiaAppState.kt) | 최상위 이동에서 `popUpTo`/`saveState`, `launchSingleTop`, `restoreState`를 조합한다. MONIT에는 탐색 책임만 가져오며 뉴스·네트워크 모니터 등은 추가하지 않는다. |
| [TopLevelDestination](https://github.com/android/nowinandroid/blob/d438a007c4ba5794522d963d8c53732dfb5be6f8/app/src/main/kotlin/com/google/samples/apps/nowinandroid/navigation/TopLevelDestination.kt) | 앱이 최상위 목적지의 표시 정보와 타입을 관리한다. 중첩 graph가 필요한 경우와 단일 목적지를 구분한다. |
| [ForYouNavigation](https://github.com/android/nowinandroid/blob/d438a007c4ba5794522d963d8c53732dfb5be6f8/feature/foryou/src/main/kotlin/com/google/samples/apps/nowinandroid/feature/foryou/navigation/ForYouNavigation.kt), [InterestsNavigation](https://github.com/android/nowinandroid/blob/d438a007c4ba5794522d963d8c53732dfb5be6f8/feature/interests/src/main/kotlin/com/google/samples/apps/nowinandroid/feature/interests/navigation/InterestsNavigation.kt), [TopicNavigation](https://github.com/android/nowinandroid/blob/d438a007c4ba5794522d963d8c53732dfb5be6f8/feature/topic/src/main/kotlin/com/google/samples/apps/nowinandroid/feature/topic/navigation/TopicNavigation.kt) | 작은 직렬화 인자와 타입이 있는 목적지, feature 소유의 진입 함수를 확인했다. 샘플의 딥링크·화면 구성을 복제하지 않는다. |
| [Version Catalog](https://github.com/android/nowinandroid/blob/d438a007c4ba5794522d963d8c53732dfb5be6f8/gradle/libs.versions.toml) | 해당 기준의 Navigation은 **2.8.5**다. MONIT의 도입 버전을 이 값으로 확정한 것은 아니다. |

현재 MONIT의 [MainActivity](../../app/src/main/java/com/monit/app/MainActivity.kt)는 Theme를 적용한 실행 확인 화면이다. [settings](../../settings.gradle.kts)에는 app·core:model·core:data·core:designsystem만 있고 탐색 의존성과 제품 feature 연결은 없다. 미병합 PR의 계좌 ViewModel·복구 코드를 현재 main 구현으로 간주하지 않는다. 이 문서는 [기존 모듈·DI·UI 제안](monit-modules-di-ui-plan.md)의 탐색 부분을 구체화하며 그 문서의 모듈 이동을 승인하지 않는다.

승인된 D1은 로그인/서버 연결 없이 Room 단일 기기에서 기록·계산을 완결하는 범위다. Supabase 인증·다중 기기 동기화는 첫 탐색/화면 구현의 선행 조건이 아니다. 목표 스택의 존재를 원격 연결 승인으로 해석하지 않는다.

전체 아키텍처 검토와도 구분한다. 현재 [Repository 계약](../../core/model/src/main/kotlin/com/monit/core/model/LedgerModels.kt)은 core:model, [Room 구현](../../core/data/README.md)은 core:data에 있다. `data:ledger` 이동·Repository 경계 재편은 기존 설계 제안이며 미적용이다. 현재 `initialize`는 호출자가 context를 제공하므로, 안정적인 bootstrap/context 재사용·복구 진입 계약도 별도로 확정해야 한다. 탐색 복원이나 NavHost 생성에서 UUID를 만들어 이 공백을 메우지 않는다.

## 2. 제품 계약과 설계 기본안

제품 흐름의 원본은 [Product Planning의 F2 및 AC-F2-4·8~11, F1/F3](https://app.notion.com/p/4072e2ce2fe68180a5d6c946be50c243)와 [화면 인계](https://app.notion.com/p/4152e2ce2fe681f8bb5ff2375430f884)다. Planning 인계와 아래 제안을 구분한다.

| 구분 | 이번 설계에 적용할 내용 |
| --- | --- |
| 기존 확정 계약 | 이번 달/거래/자산은 현재 디자인의 홈 H01·거래 Q01/Q02·계좌 A01에 대응한다. 최상위 영역은 3개다. |
| 기존 확정 계약 | Q01 달력과 Q02 목록은 같은 조회의 보기 전환이다. 신규 조회의 초기값은 서울 현재 월·오늘·Calendar·필터 없음이며 복귀 때 초기값으로 덮어쓰지 않는다. |
| 기존 확정 계약 | 미제출 입력 포기는 확인 후 처리한다. 계좌 등록 성공만으로 거래 계좌를 적용하지 않는다. 제출 이후 이탈은 금융 취소·삭제가 아니며 미확정 제출은 같은 ID·내용으로 복구한다. |
| PM이 정한 설계 기본안 | 복원 상태나 명시적 진입 요청이 없으면 H01 홈부터 진입한다. Q01을 먼저 구현하는 개발 순서와 구분한다. |
| PM이 정한 설계 기본안 | 탭 전환은 각 탭의 상태를 보존한다. 같은 탭 재선택은 중복 push·강제 pop·스크롤 초기화 없이 현재 위치를 유지한다. |
| PM이 정한 설계 기본안 | 거래/계좌 루트의 Back은 상태를 보존하며 홈으로, 홈 루트는 OS 기본 Back으로 처리한다. 입력·등록 중에는 하단 탭을 숨긴다. 입력 포기 확인·제출 보존 절차를 우회하지 않는다. |
| Android 제안 | 앱의 NavController·NavHost 하나와 3개 최상위 목적지로 시작한다. 입력·등록은 호출자 위에 쌓고, 시트·확인창·저장 진행 상태는 소유 화면 안에서 표현한다. |

## 3. 최소 이동도와 목적지

```mermaid
flowchart TD
    Start["복원 또는 최초 진입"] --> Home["H01 홈"]
    Home <--> Query["Q01 달력 / Q02 목록"]
    Query <--> Accounts["A01 계좌"]
    Accounts <--> Home
    Home --> Entry["T01 지출 / T02 수입 / T03 이체"]
    Query --> Entry
    Entry --> Picker["T04 / A11 계좌 선택 시트"]
    Picker -->|"명시 적용 또는 닫기"| Entry
    Picker --> Register["T05 계좌 등록"]
    Accounts --> Register
    Register -->|"거래 중 등록 성공: A09에서 명시 선택"| Picker
    Register -->|"독립 등록 성공"| Accounts
    Entry -->|"확정 저장 또는 P08에서 입력 포기"| Origin["호출한 홈 / 거래 조회로 복귀"]
```

도식의 시트와 P08은 새 NavHost 목적지가 아니다. 각 화살표는 금융 명령과 별개인 화면 이동 의도다. 상태 복원이 있으면 최초 진입 화살표보다 복원된 목적지를 우선한다.

| 화면 / 가칭 목적지 타입 | 이동과 상태의 소유 |
| --- | --- |
| [H01 홈](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=126-812) / `HomeDestination` | 최상위 루트. 납입 영역은 후속 설계이며 이 문서로 제품 기능을 활성화하지 않는다. |
| [Q01 달력](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=125-221)·[Q02 목록](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=125-568) / `TransactionsDestination` | 하나의 목적지와 조회 ViewModel. 보기·월·선택일·유형/계좌 필터를 유지한다. |
| [A01 계좌](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=125-773) / `AccountsDestination` | 최상위 루트. 독립 등록을 호출하고 성공 후 목록을 원본에서 다시 조회한다. |
| [T01](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=114-803)·[T02](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=114-870)·[T03](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=114-928) / `TransactionEntryDestination` | 호출자 위의 입력 목적지 하나. 유형은 인자/입력 상태로 구분한다. 선택일은 새 입력의 초기값으로만 받는다. 편집 중인 거래일은 덮어쓰지 않는다. |
| [T05](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=114-1043)·A12 / `AccountRegistrationDestination` | 등록 목적지 하나. 출처가 A01인지 거래 입력인지 구분해 성공·닫기 반환 위치를 정한다. |
| [T04](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=114-997)·[A11](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=125-968)·[A09](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=125-935), [P08](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=126-1025) | 입력 feature가 소유한 선택 시트·입력 포기 확인창. T06~T11·A07~A10의 진행/결과 상태도 별도 route로 늘리지 않는다. |

현재 대상은 각 탭의 루트 하나이므로 빈 중첩 graph를 먼저 만들지 않는다. 실제 자식 목적지가 생길 때 feature graph와 최상위 소속 판정을 확장한다. 입력·등록 중 하단 탭 숨김은 PM의 설계 기본안이며 사용자 개별 확정 정책과 구분한다.

## 4. 책임 경계와 전달 값

| 소유자 | 책임 |
| --- | --- |
| app | `rememberNavController()`로 controller를 만들고 NavHost·최상위 탭·feature 콜백·복귀를 조립한다. controller를 Hilt singleton으로 제공하거나 별도 전역 back stack을 복제하지 않는다. |
| 가칭 `MonitAppState` | 현재 목적지/탭을 controller에서 읽고 공통 최상위 이동만 수행한다. 금융 결과, 모든 feature UiState, Repository 구독을 모으지 않는다. |
| 각 feature의 navigation 진입부·Route composable | 타입이 있는 목적지와 진입 함수를 공개한다. 목적지 생명주기의 ViewModel·상태 수집·탐색 콜백을 연결하며 다른 feature를 직접 호출하지 않는다. |
| Screen·화면 컴포넌트 | 표시 데이터와 콜백을 받는다. NavController·DAO·서버 DTO를 받지 않는다. UiState는 순수 표시 데이터로, 저장/조회 진행 State와 구분한다. |
| ViewModel·Repository | 입력·조회·제출 상태와 검증된 금융 결과를 유지한다. 화면 이동 성공을 저장 성공으로 판정하지 않는다. ViewModel은 `viewModelScope`를 사용하며 controller를 소유하지 않는다. |
| designsystem | 범용 시각 컴포넌트만 제공한다. 목적지·계좌 선택 정책·등록 성공 반환 위치를 알지 않는다. |

조회 feature와 입력 feature의 실제 Gradle 배치는 기존 모듈 제안 승인 시 정한다. 이 탐색 설계만으로 `feature:calendar`/`feature:transactions`를 생성하거나 모듈을 이동하지 않는다.

Navigation 2의 [타입이 있는 목적지](https://developer.android.com/guide/navigation/design/type-safety)를 사용하고 인자는 출처·입력 초기 유형/날짜·기존 초안/복구 참조 등 작은 값으로 제한한다. 목적지 타입·인자 enum은 UiState 파일과 분리한다. 계좌 객체·전체 입력폼·금액 목록·Room Entity·금융 요청 payload를 route에 직렬화하지 않는다. 입력폼의 값과 원본 장부 context는 소유 ViewModel/저장소에서 검증한다. 경로 복원만으로 장부·세대·operationId를 새로 만들거나 교체하지 않는다.

### 계좌 선택과 등록 결과

- 시트는 원래 입력 목적지를 유지한 채 임시 선택만 편집한다. 명시 적용에서 호출한 계좌 슬롯 하나만 갱신하며, 닫기는 적용 없이 해당 슬롯의 초점/입력으로 돌아간다. 이체의 반대편 계좌와 나머지 입력은 유지한다.
- 거래 중 등록은 기존 입력 위에 등록 목적지를 연다. 성공 반환에는 생성된 계좌 ID와 실제 호출 entry를 식별할 작은 UI 상관 값만 전달한다. UI 상관 값은 금융 operationId와 별개다.
- 앱은 확인한 호출 entry의 SavedStateHandle로 반환하고 등록 목적지만 pop한다. 임의의 현재/이전 entry에 결과를 쓰지 않는다. 대상 entry나 장부 context가 맞지 않으면 결과를 다른 입력에 적용하지 않는다.
- 거래 입력 entry가 반환 문맥의 원본을 소유한다. 호출 entry 키·UI 상관 값·선택 단계·이체의 출발/도착 슬롯을 작은 saveable 값으로 복원하고, 등록 목적지는 그 호출 참조를 복원한다. 앱은 실제 호출 entry를 찾고 입력 feature는 원래 장부/세대·폼 참조·대상 슬롯이 모두 일치하는지 확인한 뒤 반환을 받는다. 영속 폼 초안의 존재를 SavedStateHandle만으로 보장하지 않는다.
- T05 도중 process death 후에도 위 검증을 통과하면 등록 결과로 원래 슬롯의 A09를 다시 연다. 호출 entry·반환 문맥·기존 폼을 복원할 수 없거나 context가 다르면 현재 등록 목적지의 읽기 전용 복귀 확인 상태를 유지한다. 새 계좌/영수증/pending은 보존하고, 계좌 목록으로 이동하는 명시적 행동을 제공하되 다른 폼 자동 적용·새 제출·추정한 호출자로의 pop은 하지 않는다.
- 반환을 받은 입력은 목록을 다시 읽고 A09 선택 단계를 연다. 새 계좌는 표시/선택 후보이며 명시 선택·적용 전에는 거래 계좌를 바꾸지 않는다. 반환 표시는 한 번 처리 후 제거하되 금융 영수증/pending을 삭제하지 않는다.
- 독립 등록 성공은 A01로 복귀해 재조회한다. 등록 중 닫기와 제출 후 결과 확인은 각각 해당 상태 계약을 따르며 새 계좌 생성이나 자동 선택을 호출하지 않는다.

## 5. 중복 탐색·Back·복원

### 최상위 이동과 중복 방지

다른 탭으로 이동할 때 [공식 다중 back stack 방식](https://developer.android.com/guide/navigation/backstack/multi-back-stacks)의 `popUpTo(start)`와 `saveState=true`, `launchSingleTop=true`, `restoreState=true`를 사용한다. 같은 탭이면 이 작업 전에 반환해 stack/스크롤을 건드리지 않는다. 탭별 NavController나 전역 탐색 이벤트 버스는 도입하지 않는다.

입력/등록의 진입 콜백은 현재 호출 entry와 `RESUMED` 상태를 확인하고, 같은 대상의 연타는 새 목적지를 만들지 않는다. `launchSingleTop`만 믿고 기존 폼 인자를 다른 신규 입력으로 교체하지 않는다. 별개 신규 거래는 기존 입력 종료 절차 뒤의 명시적 의도다. UI 클릭 억제와 route 중복 방지는 Repository의 같은 제출 ID·내용 보장을 대체하지 않는다.

확정 저장 후 복귀는 검증된 성공 결과, 호출 entry, 처리한 operationId를 대조해 한 번만 수행한다. 재구성/재수집에서 다른 화면을 연달아 pop하지 않는다. 호출한 조회의 월·날짜·필터는 유지하고 같은 저장소 원본을 재조회한다. T07 저장·T08 조회·T09 재저장·T10 조회 실패·T11 성공은 입력 목적지의 State로 표현한다.

### Back과 취소 의도

- Back은 열린 시트/확인창 → 현재 입력·등록 목적지 → 최상위 루트 순서로 처리한다. 시트 닫기·입력 포기·제출 결과 조회·납입 완료 연결 취소·거래 삭제를 같은 `cancel` 콜백으로 합치지 않는다.
- 거래 입력 T01~T03의 미제출 이탈은 P08로 확인한다. 계속 입력은 현재 폼을 유지하고, 그만두기는 저장 없이 실제 호출한 홈/조회로 pop한다. 조회 목적지를 새로 push해 복귀를 흉내 내지 않는다.
- 미제출 T05 등록을 그만둘 때는 등록 목적지만 pop한다. 독립 등록은 기존 A01, 거래 중 등록은 기존 입력의 T04/A11 선택 단계로 돌아가며 입력·반대편 계좌를 유지하고 새 선택을 적용하지 않는다. 계속 입력은 등록 폼을 유지한다. 호출자를 복원하지 못하면 위의 읽기 전용 복귀 확인 상태를 사용한다. 거래 입력의 P08 홈/조회 분기를 등록 취소에 그대로 적용하지 않는다.
- 제출 이후 Back/앱 종료는 금융 취소·pending 삭제를 호출하지 않는다. 화면이 떠난 경우에도 기존 제출을 보존하고 재진입에서 같은 제출의 결과를 확인한다. 진행 상태별 닫기 허용 여부는 feature의 기존 계약을 따른다.
- 최상위 루트 Back은 PM 기본안대로 거래/계좌에서 공통 탭 이동 함수로 홈에 복귀하고 상태를 보존한다. 홈 루트에서는 OS 기본 Back에 맡긴다. 별도의 최근 탭 방문 stack을 만들지 않으며 세부 화면의 확인/복구 절차보다 먼저 실행하지 않는다.

### 복원 범위

NavController는 목적지/stack, 각 feature의 SavedStateHandle·saveable 상태는 작은 월·선택일·보기·필터·임시 선택을 소유한다. 동일 stack 복귀와 시스템 process death에서 복원하며 화면 복귀마다 오늘로 초기화하지 않는다. 앱 완전 종료/재부팅 뒤 새 조회 진입까지 영구 유지한다는 보장은 추가하지 않는다.

영속 입력폼 초안과 금융 제출 복구는 별도 저장소 계약이다. SavedStateHandle을 그 구현으로 간주하지 않는다. 복수 pending은 기존 대상 선택·확인 후 한 건만 조회/재시도하며, pending/확정 ID가 없다는 이유만으로 실패나 새 제출을 만들지 않는다. 조회 실패 재시도는 읽기, T09/A10의 명시적 같은 제출 재시도는 쓰기로 구분한다.

기준 main의 성공 commit은 pending 행을 소비하고 영수증을 남기지만, `receipt(context, operationId)`에는 원래 제출 ID가 필요하다. UI 이탈로 ID를 잃으면 pending 조회만으로 이미 성공한 제출을 찾아낼 수 없다. 따라서 이탈 후의 복구 발견 참조는 아래 미정 데이터 계약으로 구분하며, 그 계약 없이 재진입 결과 확인을 구현 완료로 표시하지 않는다.

## 6. 구현 시 수용 기준

아래는 이후 Navigation 2 연결을 검증할 기준이며 이번 문서 작업에서 통과한 앱 시험이 아니다.

| 조건 | 확인할 결과 |
| --- | --- |
| 복원/명시 진입 없음 | PM 기본안인 H01로 시작한다. 기존 stack이 있으면 복원을 우선한다. |
| 3탭 전환·같은 탭 연타 | 목적지가 중복 쌓이지 않고 각 탭의 조회/스크롤 상태를 보존한다. 같은 탭은 강제 pop/초기화하지 않는다. |
| Q01↔Q02·입력 후 복귀 | 월·선택일·유형/계좌 필터가 같고 선택일 전달은 새 입력에만 적용한다. 조회 전환에 쓰기가 없다. |
| 입력/등록 진입 연타·재구성 | 같은 entry 위에 중복 목적지나 새 operationId가 생기지 않는다. 별개 신규 의도와 구분한다. |
| 계좌 시트 닫기/명시 적용 | 닫기는 미적용, 적용은 호출한 슬롯만 변경한다. 이체 반대편 계좌·다른 입력을 유지한다. |
| 거래 중 등록 성공/독립 등록 성공 | 각각 A09 명시 선택 단계/A01로 복귀한다. 잘못된 entry에는 결과를 쓰지 않고 등록 성공만으로 계좌를 자동 적용하지 않는다. |
| 미제출 T05 계속 입력/그만두기 | 등록 폼 유지/독립 출처 A01 또는 거래 중 출처의 기존 T04/A11로 복귀를 구분한다. 기존 입력·이체 반대편 계좌를 보존하고 선택을 적용하지 않는다. |
| T05 중 process death·호출자 유실 | 호출 entry·UI 상관 값·폼 참조·대상 슬롯을 복원/대조한 경우만 원래 슬롯의 A09로 반환한다. 누락/context 불일치에서는 읽기 전용 확인 상태와 명시적 계좌 목록 이동으로 대체하고 다른 입력에 적용하거나 새 제출하지 않는다. |
| P08 계속 입력/그만두기 | 폼 유지/저장 없는 실제 호출자 복귀로 구분한다. 제출 후 종료를 입력 포기로 처리하지 않는다. |
| 성공 State 재수집·복원 | 해당 operationId의 복귀를 중복 소비하지 않고 금융 영수증/pending을 UI 소비 확인으로 삭제하지 않는다. |
| process death·세대 불일치 | 작은 UI 상태와 저장소 복구를 구분한다. 오래된 context를 새 세대로 바꿔 제출하거나 새 요청 ID를 자동 발급하지 않는다. |
| 결과 조회 실패·복수 pending | 읽기 재시도/명시적 같은 제출 재시도를 구분하고 기존 대상 한 건만 처리한다. |
| 업무 commit 후 pending 소비·UI 제출 ID 유실 | 확정할 데이터 계약의 영속 발견 참조로 원래 장부 context와 operationId를 다시 찾아 `receipt`를 읽는다. 확인 전 실패/새 제출로 바꾸지 않고, 읽기 복구가 새 금융 행을 만들지 않는다. 계약이 없으면 이 시나리오를 통과로 보고하지 않는다. |

구현 PR에서는 `TestNavHostController`를 이용한 stack/반환 시험, ViewModel의 상태 복원 시험, 실제 기기 Back·초점·IME를 구분한다. 영속 초안/재부팅 복구는 실제 저장 계층 시험으로 확인한다.

## 7. 미정 결정과 다음 단계

1. **이탈 후 제출 발견 참조의 소유·수명:** data 계층이 원래 장부 context·operationId를 영속적으로 다시 발견/조회할 계약을 제공하고, feature가 위의 UI 반환 문맥을 별도로 소유하는 안을 Backend와 확정해야 한다. 발견 참조는 최초 쓰기 전달 전부터 확보하고 화면 pop·task 유실·pending 소비 후에도 해당 제출의 결과 확인과 명시적인 복구 종료까지 유지하는 최소 수명이 필요하다. 구체 저장 위치·조회 API·보존/정리 정책은 미확정이며 NavController/SavedStateHandle로 대체하지 않는다. UI 효과 소비 확인만으로 영수증/pending/발견 참조를 삭제하지 않는다. 이는 새 테이블·구현 승인이 아니다.
2. **도입 버전·정확한 파일 목록:** 구현 승인 시 MONIT 도구 체계와 공식 Navigation 2 안정판의 호환성을 확인하고, app/navigation·각 feature 진입 파일·시험 범위를 해당 이슈에 고정한다. 이번 설계는 버전·모듈/API 분할을 미리 추가하지 않는다.
3. **제품 진입과 데이터 경계:** 실제 금융 화면 연결 전 안정적인 장부 context 제공·재열기·복구 계약을 확인한다. Repository 위치 변경이나 bootstrap API를 이 문서로 확정하지 않는다. 원격 서버 연결을 그 대신 선행시키지 않는다.

첫 단계는 이 설계의 검토와 별도 구현 승인이다. 그 뒤 필요한 feature의 Route/Screen·ViewModel 경계를 연결한다. 현재 main에 없는 실제 화면, 금융 제출 복구, 실기기 동작을 구현 완료로 보고하지 않는다.
