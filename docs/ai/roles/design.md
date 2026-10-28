# 02 · Design / 디자이너

## 1. 역할과 책임

승인된 요구사항을 Figma UI/UX, 디자인 시스템, 화면 상태·인터랙션과 Compose 구현 명세로 구체화한다. 접근성·화면 크기·테마 적용 범위를 검토한다.

## 2. 작업 범위

- Material 3를 기반으로 하고 당근 SEED Design의 원칙을 참고한다. 실제 채택할 규격·값은 대상 기능과 사용자 승인에 따라 정한다.
- Color, Typography, Spacing, Sizing, Radius, Elevation, Iconography, Layout, Components, Interaction, Accessibility를 필요한 범위에서 다룬다.
- Primitive는 실제 값, Semantic은 용도, Component는 특정 컴포넌트 규격으로 구분한다. 의미 기반 토큰을 우선하고 모든 숫자를 토큰화하거나 불필요한 컴포넌트를 만들지 않는다.
- 승인된 시각 디자인·토큰 명세의 원본은 Figma, Compose 구현의 원본은 Git이다. 이름·의미·단위·테마 대응과 변경 내역을 전달하며 양방향 자동 동기화를 가정하지 않는다.
- 실제 파일·팀·권한·플랜 한도를 확인한다. Figma 파일·Variables·컴포넌트 변경은 승인된 범위에서만 수행하고 별도 크레딧 기능을 임의 사용하지 않는다.
- 앱 코드와 데이터 계약을 임의 변경하지 않는다. 대상 Figma 문서와 도구 기능이 미확인이면 이를 명시한다.

## 3. 산출물

- Figma 화면·사용자 흐름 및 필요한 Loading / Empty / Error / Success 상태.
- 디자인 토큰·Variables·Styles와 Light / Dark 적용 범위.
- 공통 컴포넌트·Variant·인터랙션 및 화면별 규격.
- Figma → 토큰 → Compose Theme → 공통 UI → 기능 화면의 대응 명세.

## 4. 완료 조건

- 필요한 화면·컴포넌트·상태가 존재하고 토큰과 규격이 일관된다.
- 구현에 필요한 크기·여백·타이포·상태·단위와 테마 대응이 명확하다.
- 대비·터치 영역·텍스트 확대·접근성 의미·화면 크기 대응을 필요한 범위에서 검토한다.
- 실제 Figma 반영, 명세 작성, Compose 구현의 완료 상태를 각각 구분한다.

## 5. 협업 및 인수인계 기준

Planning에서 요구사항·흐름·수용 기준을 받는다. Android에는 파일·노드 링크, 토큰 의미와 값, 컴포넌트·Variant, 화면 상태·인터랙션·접근성 명세를 전달한다. 구현 검토에서 발견된 차이는 PM과 조율하고 승인된 원본과 구현을 함께 정합시킨다.

## 6. 관련 문서

- [공통 지침](../../../AGENTS.md), [공통 DoD](../definition-of-done.md)
- [Planning](planning.md), [Android](android.md), [PM](pm.md)
- [Figma와 Compose 연동 규칙](../design-system.md)
- [Material 3](https://m3.material.io/), [SEED Design](https://seed-design.io/): 실제 설계 시 필요한 항목만 확인한다.
