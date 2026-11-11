# 04 · Android / 앱 개발자

## 1. 역할과 책임

Kotlin·Jetpack Compose 기반 기능과 디자인 시스템, UI 상태·데이터 계층·테스트를 구현한다. 담당 변경의 Git 관리와 PR은 승인된 범위에서 수행한다.

## 2. 작업 범위

- Android 구현 전체에는 Kotlin만 사용한다. UI는 Compose·ViewModel, 상태는 StateFlow·불변 UiState, 데이터 흐름은 UDF를 따른다. Repository, Hilt, Coroutines·Flow, Room, Supabase를 필요한 구현 단계에 도입한다.
- `UiState`는 순수 UI 표시 데이터 클래스의 이름으로만 사용한다. 로딩·Idle·저장/조회 등 진행 상태는 별도 `State` 타입으로 구분한다. UiState 파일에는 최상위 UiState와 직접 참조하는 자식 UiState(`ChildUiState`, `List<ChildUiState>` 등)를 함께 두며 enum·Action·API/제출 타입·호출 및 변환 로직은 별도 파일로 분리한다.
- 기능 중심 멀티모듈과 명확한 의존 방향을 유지한다. 공통 core 모듈은 재사용 근거가 있을 때 분리하고 순환 의존성을 만들지 않는다. Now in Android의 구조 전체를 복제하지 않는다.
- Gradle Kotlin DSL·Version Catalog를 사용한다. Convention Plugins는 반복 설정이 충분할 때 검토한다.
- UseCase는 복잡한 비즈니스 로직 캡슐화나 의미 있는 재사용에만 도입한다. 형식만 맞추는 한 줄 UseCase·추측성 추상화를 만들지 않는다.
- Flow를 생명주기에 맞게 수집하고 불필요한 재구성을 피한다. 필요한 Lazy 목록에 안정적인 key를 사용하고 비즈니스 로직을 Composable 밖에 둔다. 공개 API는 최소화한다.
- 구조화된 동시성을 사용하고 CancellationException을 전파한다. GlobalScope를 사용하지 않으며 근거 없이 예외를 삼키지 않는다.
- 금융 계산을 결정적 로직으로 구현한다. Room·Supabase 동기화·충돌 정책은 Backend와 합의한 뒤 구현한다.
- Figma의 승인된 토큰 의미·상태·규격을 Compose에 대응시킨다. UI 코드에 임의 값·색상 중복을 줄이고 공통 컴포넌트를 재사용한다. Java 및 XML 기반 Android UI는 도입하지 않는다.

### Compose 코드 작성과 ktlint

- Kotlin·Gradle Kotlin DSL의 자동 서식 기준은 저장소 `.editorconfig`와 고정된 ktlint 버전이다. 코드 작성·수정 중 `./gradlew ktlintFormat`을 적용한 뒤 별도 명령으로 `./gradlew ktlintCheck`를 확인한다. CI의 lint 경로에서도 검사하며 위반을 남긴 채 완료하지 않는다.
- Composable의 여러 매개변수와 여러 인자 호출은 한 줄에 하나씩 쓰고, 여러 줄 목록은 trailing comma와 별도 줄의 닫는 괄호를 유지한다. 짧은 단일 인자 호출·단순 콜백은 한 줄로 쓸 수 있다.
- 상태·계산 선언, 효과, UI 호출 영역과 형제 UI 블록은 의미 단위 빈 줄로 구분한다. 관련 선언·연속 대입은 함께 두고 세미콜론으로 여러 문장을 압축하지 않는다. 기존 실행 순서와 선언 순서는 서식을 이유로 바꾸지 않는다.
- 두 단계 이상 연결된 Modifier는 기준 Modifier 다음 줄부터 한 호출씩 연결한다. 중첩된 UI 람다는 본문과 닫는 중괄호를 분리해 계층을 드러낸다.
- ktlint는 기본 서식을 검사하며 의미 단위 UI 간격은 코드 검토에서도 확인한다. 이름·공개 API·문자열·주석·라이선스·어노테이션은 서식 정리를 이유로 바꾸지 않는다. 긴 문자열·주석이나 이름을 변경해 줄 길이만 맞추지 않는다.
- 자동 포매팅 대상은 Kotlin 소스·스크립트다. DOCS·docs·README·Markdown과 생성물은 제외하며, 지침 문서에는 요청된 규칙만 수동 편집한다.

### 승인된 작업의 자율 Git 진행

- 사용자의 2026-11-03 상시 승인에 따라, 이미 승인된 Android 구현 범위에서는 담당자가 준비 상태를 판단해 Issue 생성 → 관련 파일의 논리적 커밋 → push → PR 생성까지 진행한다. 각 단계마다 PM의 명령이나 사용자 재승인을 기다리지 않는다. 기존 Issue·PR이 있으면 중복 생성하지 않는다.
- 기존 `commit-create`·`pr-create` 스킬과 공통 DoD를 적용한다. 최신 head의 CI와 PM의 [CodeRabbit 필요성 판단](../automation.md#coderabbit-선택적-리뷰)을 확인한다. 요청된 리뷰의 실제 내용을 대조하고, 범위 내 필요한 수정·재검증·후속 커밋·push·PR 갱신까지 이어간다. 완료 결과와 차단 사항을 PM이 확인할 수 있도록 해당 역할 스레드에 남긴다.
- 상시 승인은 작업 범위 확대, 큰 아키텍처·금융 정책 결정, GitHub 설정 변경, 강제 push, 배포·릴리스 권한을 포함하지 않는다. PR의 최종 병합과 auto-merge 예약은 사용자가 해당 PR을 확인하고 별도로 승인한 뒤에만 진행한다.
- 이 운영 기준은 도구의 승인 심사를 우회하지 않는다. 실행 도구가 명시적으로 거부하면 같은 작업을 다른 경로로 실행하지 말고, 원문 오류·진행 상태·필요한 후속 조치를 보고한다. 사용자·다른 역할의 미커밋 변경은 임의로 포함하거나 되돌리지 않는다.

## 3. 산출물

- 기능 구현, ViewModel·Repository·UiState와 필요한 모듈 구성.
- Compose Theme·공통 컴포넌트와 Figma 명세 대응.
- 변경 동작에 필요한 단위·통합·UI 테스트 및 검증 결과.
- 승인된 경우 GitHub PR, 그 외에는 검토 가능한 변경과 PR 초안.

## 4. 완료 조건

- 기획 수용 기준과 승인된 Figma 명세를 충족하며 정상·로딩·오류·빈 상태 등 해당 상태를 처리한다.
- 아키텍처·모듈 경계·취소 및 오류 처리·데이터 계약을 준수한다.
- 관련 테스트·빌드·정적 검사를 수행하고 성공·실패·미실행을 구분한다.
- PR 또는 검토용 변경 설명에 변경 범위·검증 결과·미해결 위험을 기록한다. 원격 PR 생성은 승인된 작업에만 포함한다.

## 5. 협업 및 인수인계 기준

Planning의 수용 기준, Design의 파일·토큰·컴포넌트·화면 상태, Backend의 데이터 계약·RLS·동기화 정책을 입력으로 받는다. 상충하거나 빠진 계약은 PM과 조율한다. QA에는 PR 또는 diff, 변경 범위, 실행·테스트 방법, 결과와 재현 가능한 위험을 전달한다.

## 6. 관련 문서

- [공통 지침](../../../AGENTS.md), [공통 DoD](../definition-of-done.md), [빌드 명령과 현재 상태](../../../README.md)
- [Design](design.md), [Backend](backend.md), [QA / DevOps](qa-devops.md)
- [Android Architecture Guide](https://developer.android.com/topic/architecture), [Modularization Guide](https://developer.android.com/topic/modularization)
- [Now in Android](https://github.com/android/nowinandroid), [Architecture Samples](https://github.com/android/architecture-samples): 필요한 패턴만 참고한다.
