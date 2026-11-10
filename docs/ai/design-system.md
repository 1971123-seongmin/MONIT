# Figma와 Compose 연동 규칙

## 현재 범위와 원본

현재 앱은 Compose 기반 실행 확인용 화면이며 `core:designsystem`의 `MonitTheme`만 적용한다. 공통 컴포넌트는 모듈에 구현되어 있지만 앱 화면에서는 아직 사용하지 않는다. 제품 기능 화면 구현과 최신 Figma 설계의 전체 반영은 별도 작업이다.

현재 구현 범위·Figma 페이지별 링크·이전 구현의 참조 기준·검증 결과는 [디자인 시스템 README](../../core/designsystem/README.md)에서 관리한다. 이 문서에는 전달·매핑·변경 절차만 유지하며 토큰 개수와 화면 목록을 중복 작성하지 않는다.

파일 위치는 선택된 팀의 [Folders → MONIT](https://www.figma.com/files/team/1443528126943486080/folder/679506204)이다. 새 파일도 이 폴더를 사용한다. 도구가 팀 Drafts에 생성하면 MONIT으로 이동하고 폴더 목록에서 확인한 뒤 완료로 보고한다.

Material 3의 접근성·상태 원칙과 SEED의 의미 기반 토큰·재사용 원칙을 적용한다. 외부 디자인 시스템의 제품 색상·규격을 그대로 복사하거나 공식 SEED Compose SDK를 사용한다고 가정하지 않는다.

## 원본과 토큰 구조

| 구분 | 관리 기준 |
|---|---|
| Figma | 승인된 시각 디자인·토큰·상태 명세의 원본. Primitive → Semantic → Component 별칭과 Light/Dark 대응을 관리 |
| Git | 실행 가능한 Theme·컴포넌트·폰트·테스트의 원본. 검토한 Figma 기준을 명시해 구현 |
| 디자인 시스템 README | 최신 설계와 현재 구현의 차이, 참조 노드, 사용 경계와 검증 방법을 연결 |
| 이 문서 | 원본 간 전달·매핑·변경 절차. 전체 색상 값과 상태 목록 사본을 유지하지 않음 |

Figma의 slash 이름은 의미 계층을 나타내며 Kotlin 식별자에 그대로 사용하지 않는다. Figma의 ANDROID code syntax나 Code Connect 조회 결과만으로 MONIT 구현 또는 자동 동기화가 완료됐다고 판단하지 않는다. 별도 Code Connect 등록은 필요성과 승인 범위를 확인한 후 검토한다.

## Compose 매핑 기준

| 디자인 의미 | 현재 구현 또는 적용 기준 |
|---|---|
| 기본 색상 역할 | `MaterialTheme.colorScheme` |
| 추가 의미 색상 | `MonitTheme.colors`. M3 기본 상태 표현과 중복되지 않도록 확인 |
| 간격·크기 | `MonitTheme.spacing`, `MonitTheme.sizes` |
| 모서리 | `MaterialTheme.shapes`와 컴포넌트별 규격 |
| 글자 스타일 | `MaterialTheme.typography`. 포함된 Noto Sans KR과 라이선스는 모듈 README 참조 |
| 컴포넌트 토큰 | 공통 컴포넌트 내부에서 대응. 기능 화면이 Primitive 값을 직접 복제하지 않음 |

Figma 치수는 설계 좌표다. Android 레이아웃은 dp, 글자 크기·행간은 sp로 구현하고 실제 기기의 density·fontScale에서 확인한다. 설계 프레임 크기를 모든 기기에 고정하지 않는다. Light/Dark는 동일한 의미 토큰을 사용하고 **기기 설정에 따른 테마 전환·동적 색상 비사용** 정책을 유지한다.

## 상태와 접근성

- 정상·누름·포커스·비활성·진행 상태 중 해당 컴포넌트에 필요한 상태를 명세하고 실제 상호작용과 대조한다.
- 비활성·진행 상태에서는 허용하지 않는 입력을 차단하고 접근성 서비스에 상태를 전달한다. 저장 원자성·중복 방지는 Repository의 별도 책임이다.
- 화면마다 원본 컴포넌트의 상태 Variant를 복제하지 않고 인스턴스를 사용한다.
- 최소 터치 영역은 48dp를 기준으로 하며 큰 글자에서도 라벨 잘림 없이 높이가 늘어날 수 있어야 한다. 색상만으로 상태를 구별하지 않는다.
- Figma 시각 확인, Compose 호스트 테스트, 실제 기기 검증을 구분한다. TalkBack·IME·시스템 바·화면 복귀와 최신 디자인 일치는 실행한 범위만 보고한다.

## 변경과 검증 절차

1. Design이 변경 이유·대상 토큰/노드·영향받는 상태를 정리하고 필요한 승인을 확인한다.
2. Figma에서 필요한 Primitive·Semantic·Component를 갱신하고 Light/Dark 별칭·scope·미연결 값·컴포넌트 binding을 검증한다.
3. Android에 파일·노드 링크, 변경 토큰, 기준 버전과 상태 명세를 전달한다. 이전 노드는 구현 근거 추적용으로 구분하고 새 작업에는 최신 전달 기준을 사용한다.
4. Android는 승인된 구현 작업에서 Theme → 공통 컴포넌트 → 기능 화면으로 적용한다. 최신 설계의 존재를 구현 완료로 해석하지 않는다.
5. QA가 영향받는 상태·대비·터치 영역·텍스트 확대·레이아웃과 필요한 기기 동작을 검증한다. 미검증 항목을 남기고 디자인 시스템 README의 구현·검증 상태를 갱신한다.

협업 로그에는 중요한 변경과 원본 링크만 남긴다. 매번 전체 설계·코드를 다시 읽거나 전체 빌드를 반복하지 않고, 변경 영향과 [검증 신선도](automation.md#검증-신선도)를 기준으로 검사 범위를 선택한다.

## 관련 문서

- [디자인 시스템 사용 계약](../../core/designsystem/README.md)
- [Design 역할](roles/design.md), [Android 역할](roles/android.md), [협업 운영](workflow.md)
