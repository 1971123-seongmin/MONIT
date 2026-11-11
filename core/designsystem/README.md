# MONIT 디자인 시스템

Material 3 Compose와 MONIT의 승인된 색상·타이포·상태 역할을 사용한다. 기기 Light/Dark를 따르고 동적 색상은 사용하지 않는다. 제품 화면·ViewModel·Room·서버·금융 정책은 이 모듈에 포함하지 않는다.

## 디자인 원본과 구현

시각 원본은 선택된 팀의 Folders → MONIT에 있는 [Figma Foundations](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=127-1028), [19개 원본 컴포넌트](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=120-1086), [현재 인계](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=131-2)다. 사용 예제와 원본 컴포넌트를 구분하며 Figma의 전체 제품 화면을 구현했다고 해석하지 않는다. M3의 상태·접근성과 SEED의 선택·시트·피드백 구성 원칙을 참고하며 웹 코드를 복제하지 않는다.

기본 설계 좌표는 360×780, 좌우 16·콘텐츠 328, 달력만 좌우 12·콘텐츠 336이다. 앱 크기를 고정하지 않으며 최소 48dp 터치·24dp 아이콘·가변 높이를 유지한다. 색상과 다섯 글자 스타일은 [theme](src/main/kotlin/com/monit/core/designsystem/theme)에서 관리한다. Figma Variables·Text Styles는 변경하지 않았다.

각 family에는 아래 상태를 보여주는 Compose Preview를 작성했다. 상태 수는 Figma의 설계 분류이며 독립 제품 기능 수가 아니다. 구현·Preview 작성, 실제 렌더, 접근성 동작 검증은 각각 구분한다.

| Figma family · master | Compose API | Preview에 포함한 상태 |
|---|---|---|
| [ActionButton · 127:995](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=127-995) | `MonitButton` | Default·Pressed·Focused·Disabled·Loading |
| [Field · 109:284](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=109-284) | `MonitAmountField` | Default·Focused·Error·Disabled |
| [AccountCard · 109:381](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=109-381) | `MonitAccountCard` | Unknown·Selected·Disabled·Confirmed(표시 예시) |
| [TransactionRow · 109:367](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=109-367) | `MonitTransactionRow` | Income·Expense·Transfer |
| [CalendarCell · 113:404](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=113-404) | `MonitDateCell` | Default·Today·Selected·Future·Blank |
| [Badge · 109:223](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=109-223) | `MonitBadge` | Neutral·Selected·Error·Disabled |
| [FilterChip · 109:231](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=109-231) | `MonitFilterChip` | Default·Selected·Disabled |
| [TopBar · 109:238](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=109-238) | `MonitTopBar` | Root·Back·Close |
| [SegmentedControl · 109:253](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=109-253) | `MonitSegmentedControl` | Expense·Income·Transfer·Calendar·List |
| [StatusPanel · 109:335](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=109-335) | `MonitStatusPanel` | Empty·Loading·Error·Saving·Checking·CheckFailed·Success |
| [MonthSummary · 109:396](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=109-396) | `MonitMonthSummary` | Normal·Empty |
| [BottomNavigation · 113:333](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=113-333) | `MonitBottomNavigation` | Overview·Transactions·Accounts |
| [BottomSheet · 113:354](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=113-354) | `MonitAccountBottomSheet`·`MonitAccountSheetContent` | Account·Empty |
| [Dialog · 113:373](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=113-373) | `MonitDialog`·`MonitDialogContent` | Discard·DeleteFuture(비활성 예시) |
| [Snackbar · 113:388](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=113-388) | `MonitSnackbar` | Saved·Retry |
| [Skeleton · 113:393](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=113-393) | `MonitSkeleton` | Row·Card |
| [CalendarHeader · 113:426](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=113-426) | `MonitCalendarHeader` | Month |
| [PaymentStatusRow · 113:437](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=113-437) | `MonitPaymentStatusRow` | Pending·Completed·Skipped(표시 예시) |
| [DatePicker · 113:449](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=113-449) | `MonitDatePicker` | Selected·Error |

기존 5개에서 입력은 내부 고정 라벨·radius 12, 거래는 제목/금액/계좌 세 줄, 계좌 선택은 카드 구성으로 바뀌었다. `MonitAccountPicker`는 ID 선택·미확인/확인한 0원·오류/잠금 계약을 유지하며 카드를 재사용한다. 날짜는 48dp/40dp 원과 확대·통합 semantics를 유지한다. Field의 Focused+Error는 오류 문구와 오류 테두리를 유지하며 별도 시각 variant를 만들지 않는다.

TopBar·CalendarHeader의 24dp 아이콘은 Design에서 원본 key가 Material 3 Design Kit임을 확인한 `Icons.AutoMirrored.Filled.ArrowBack/ArrowForward`, `Icons.Filled.Close`를 재사용한다. RTL에서는 화살표 방향만 반전하고 이전/다음 행동 의미를 유지한다. 별도 Code Connect 등록·Figma 쓰기는 하지 않았다.

Dark Skeleton은 원본의 `surfaceContainer`와 `disabledBackground`가 같은 색이어서 바가 보이지 않는다. Design 확인에 따라 Skeleton 내부의 바에만 기존 `surface`를 대응한다. Light와 전역 disabled 토큰·Figma는 유지하며, 이 차이는 로딩 자리 표시의 식별성 보완이다.

선택된 날짜가 새 정책으로 비활성이 되면 기존 disabled 배경/글자와 primary 선택 외곽선을 사용한다. Design 확인에 따른 복합 상태 보완이며 활성 선택 색은 유지한다. CalendarCell 카탈로그에는 Figma 5상태 외에 이 조합을 추가해 렌더와 클릭 차단을 확인한다. 계좌 선택기는 잔액 null을 Card까지 보존하며 기준일 라벨은 별도 설명에 표시한다.

## 사용 계약

- `MonitButton`의 disabled/loading은 입력을 잠근다. 처리 문구와 접근성 안내를 지정할 수 있다. 이 버튼만 직접 누름 색상·외부 포커스 링을 제공하므로 ripple을 조정하며, 다른 M3 컴포넌트의 ripple은 유지한다. 주변 4dp를 clip하지 않는다.
- `MonitAmountField`는 BasicTextField와 M3 Surface·Text로 내부 라벨을 구성한다. 빈칸·단독 `-`·소수·긴 붙여넣기와 커서를 보존하며 금액으로 변환하지 않는다. 호출자가 검증·IME 완료·오류 초점을 처리한다. 오류는 입력에 연결하며 타이핑마다 live announcement를 만들지 않는다.
- `MonitAccountCard`의 선택과 잔액 확인은 독립한다. `balanceText=null`은 미확인이며 0원으로 바꾸지 않는다. 선택 불가 사유를 안내하고 라디오 역할의 한 대상으로 읽는다. `MonitAccountPicker`의 ID는 유일해야 한다.
- `MonitAccountBottomSheet`는 소유자가 전달한 선택 ID를 명시적 적용 때만 반환한다. 추가 계좌를 자동 적용하지 않는다. 최대 높이 90%, 목록만 스크롤하며 로딩 때 닫기·적용·선택을 잠근다. 조회 중·실패를 미등록으로 표시하지 않는다. 화면 복귀 초점은 소유자가 처리한다.
- 거래 행·월 합계·납입 상태·피드백은 표시 값만 받는다. 계산·반올림·완료 판정·삭제·저장을 실행하지 않는다. Confirmed·DeleteFuture·PaymentStatusRow의 예시가 제품 API 활성화를 뜻하지 않는다.
- 날짜 선택기는 표시 월·선택일·오늘·선택 가능 날짜 정책을 받는다. 계좌 기준일/거래일 정책과 월 이동 시 선택일 보정·최신 응답 판정은 소유자가 처리한다. 미래 날짜는 조회 가능하며 기록 제한은 별도 정책이다. 빈 날짜는 비상호작용이며 320dp/큰 글꼴에서 7열을 줄이지 않고 가로 스크롤한다.
- `MonitStatusPanel`은 호출자가 구분한 상태를 표시한다. pending 없음만으로 저장 실패를 추정하거나 확정 응답 전에 성공을 표시하지 않는다. 결과 불명은 Snackbar 한 줄로 축약하지 않는다. `MonitSnackbar`는 SnackbarHost 슬롯에 넣어 수명을 관리한다.
- Dialog는 M3의 모달 focus/뒤로/바깥 클릭을 사용한다. Preview는 별도 창을 여는 대신 같은 Content를 보여주므로 모달 동작 검증을 대신하지 않는다.

## 공통 클릭 Modifier

[MonitClickModifiers.kt](src/main/kotlin/com/monit/core/designsystem/modifier/MonitClickModifiers.kt)에 두 확장을 둔다.

- `clickableWithoutRipple`: 직접 시각 피드백을 제공하는 대상에서만 사용한다. foundation clickable의 enabled·role·onClickLabel·키보드·포커스를 유지한다.
- `clickableWithCooldown`: composition 인스턴스별로 첫 클릭을 즉시 허용하며 수락한 클릭부터 간격을 센다. 억제 클릭은 대기 시간을 늘리지 않는다. 재구성 뒤에도 간격을 유지하고 최신 callback을 호출한다. 기본 ripple을 유지하며 필요한 대상만 `withoutRipple=true`로 지정한다.

대상의 최소 터치 크기는 호출자가 보장한다. M3 Button에 위 clickable을 중첩하지 않는다. cooldown은 UI의 빠른 반복 입력만 줄이며 loading 잠금과 Repository의 operationId 기반 금융 idempotency를 대신하지 않는다. `composed`·전역 timestamp·실제 지연 타이머는 사용하지 않는다.

cooldown Modifier는 대상마다 별도로 생성한다. 반환한 하나의 Modifier를 여러 대상에 재사용하면 gate도 공유한다. 비활성화/재활성화는 마지막 수락 시점을 지우지 않고, 간격을 바꾸면 그 시점에 새 간격을 적용한다. composition에서 제거되면 gate도 사라진다. ripple을 없앤 대상에는 눌림 색상·포커스 외곽선 등 동등한 피드백을 별도로 제공해야 한다.

## Preview와 검증

[MonitFamilyPreviews.kt](src/main/kotlin/com/monit/core/designsystem/preview/MonitFamilyPreviews.kt)는 family별 Light/Dark 360dp와 Light/Dark 320dp·200%를 제공한다. Hilt·DB·네트워크 없이 고정 날짜와 합성 데이터만 사용한다. [Figma Prevue 대응표](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=98-796)와 함께 비교한다.

```powershell
.\gradlew.bat :core:designsystem:testDebugUnitTest :app:assembleDebug :core:designsystem:lintDebug :app:lintDebug --console=plain
```

`MonitComponentTest`는 테마·대비·문자열/IME·잠금·ID 선택·미확인/0원·최소 터치·큰 글꼴·키보드를 검사한다. `MonitInteractionTest`는 cooldown의 첫/경계 클릭·인스턴스 분리·재구성·최신 callback·disabled, no-ripple의 role/label/focus/Enter, 명시적 계좌 적용·조회 상태·날짜 정책·확인 중 잠금을 검사한다.

`MonitCatalogRenderTest`는 19 family × 네 조건의 호스트 PNG 76개를 `build/reports/designsystem/screenshots/testDebugUnitTest`에 만든다. 실제 View의 Native Canvas를 사용한다. 이는 Prevue renderer·자동 픽셀 회귀·실기기 렌더의 성공 증거가 아니다. 필터 실행 때는 해당 조건 이미지만 남으며 전체 실행으로 다시 생성한다. 실제 Prevue 비교의 대상 head·조건·결과는 해당 PR에 별도로 기록한다.

다음은 코드 검토용 **호스트** 렌더이며 Prevue 증거가 아니다.

| Light 입력 · 360dp | Light 선택 시트 본문 · 360dp |
|---|---|
| ![내부 라벨과 입력 상태](docs/current-light-field.png) | ![계좌 선택·미등록](docs/current-light-sheet.png) |

| Dark Skeleton · 320dp/200% | Dark 거래 행 · 320dp/200% |
|---|---|
| ![로딩 자리 표시 식별성](docs/current-dark-skeleton.png) | ![큰 글자 거래 표시](docs/current-dark-large-records.png) |

실기기 TalkBack 읽기 순서·IME·시스템 바/insets·모달 복귀 초점과 제품 화면의 저장/복구 동작은 별도 검증 범위다. 지정 기기가 연결되지 않으면 다른 기기로 대체해 통과 처리하지 않는다.

## 글꼴과 라이선스

[공식 google/fonts Noto Sans KR](https://github.com/google/fonts/tree/b38c5c93af322c45f633e17ac440ec1e6c94d489/ofl/notosanskr)의 variable TTF를 수정 없이 포함한다. 400/500 weight를 명시하고 실행 시 네트워크를 사용하지 않는다. 라이선스는 APK의 `assets/fonts/NotoSansKR-OFL.txt`에 포함한다.

- 고정 revision: `b38c5c93af322c45f633e17ac440ec1e6c94d489`
- SHA-256: `194018e6b2b293a7964f037b25c0249ce1418bc9ab3c971060a03aa57861e252`
- 크기: 10,414,588 bytes. 폰트 분할/서브셋 최적화는 별도 검토한다.
