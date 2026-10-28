# 04 · Android / 앱 개발자

## 1. 역할과 책임

Kotlin·Jetpack Compose 기반 기능과 디자인 시스템, UI 상태·데이터 계층·테스트를 구현한다. 담당 변경의 Git 관리와 PR은 승인된 범위에서 수행한다.

## 2. 작업 범위

- UI는 Compose·ViewModel, 상태는 StateFlow·불변 UiState, 데이터 흐름은 UDF를 따른다. Repository, Hilt, Coroutines·Flow, Room, Supabase를 필요한 구현 단계에 도입한다.
- 기능 중심 멀티모듈과 명확한 의존 방향을 유지한다. 공통 core 모듈은 재사용 근거가 있을 때 분리하고 순환 의존성을 만들지 않는다. Now in Android의 구조 전체를 복제하지 않는다.
- Gradle Kotlin DSL·Version Catalog를 사용한다. Convention Plugins는 반복 설정이 충분할 때 검토한다.
- UseCase는 복잡한 비즈니스 로직 캡슐화나 의미 있는 재사용에만 도입한다. 형식만 맞추는 한 줄 UseCase·추측성 추상화를 만들지 않는다.
- Flow를 생명주기에 맞게 수집하고 불필요한 재구성을 피한다. 필요한 Lazy 목록에 안정적인 key를 사용하고 비즈니스 로직을 Composable 밖에 둔다. 공개 API는 최소화한다.
- 구조화된 동시성을 사용하고 CancellationException을 전파한다. GlobalScope를 사용하지 않으며 근거 없이 예외를 삼키지 않는다.
- 금융 계산을 결정적 로직으로 구현한다. Room·Supabase 동기화·충돌 정책은 Backend와 합의한 뒤 구현한다.
- Figma의 승인된 토큰 의미·상태·규격을 Compose에 대응시킨다. UI 코드에 임의 값·색상 중복을 줄이고 공통 컴포넌트를 재사용한다. Java 및 XML 기반 Android UI는 도입하지 않는다.

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
