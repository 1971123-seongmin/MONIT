# Figma와 Compose 연동 규칙

## 현재 범위와 상태

[MONIT · Design System · Draft](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=3-16)는 사용자가 선택한 Education 팀에 만든 초기 검토용 디자인이다. 시각 디자인 승인과 앱 적용은 아직 수행하지 않았다. 현재 앱은 TextView 임시 화면이며 Compose·Theme·공통 UI·Code Connect는 구현되어 있지 않다.

파일 위치는 선택된 팀의 [Folders → MONIT](https://www.figma.com/files/team/1443528126943486080/folder/679506204)이다. 새 파일도 이 폴더를 사용한다. 도구가 팀 Drafts에 생성하면 MONIT으로 이동하고 폴더 목록에서 확인한 뒤 완료로 보고한다.

이번 범위는 3단계 토큰 40개, Light/Dark 모드, 글자 스타일 3개, 버튼 1종의 5개 상태와 테마 비교 보드다. 전체 화면·브랜드 확정·아이콘·애니메이션·Elevation 라이브러리는 첫 기능 요구사항에 맞춰 이후 확장한다.

Material 3 라이브러리를 조회하여 버튼·변수 자산 존재를 확인했다. 여러 크기·모양을 포함한 전체 키트를 가져오지 않고 MONIT의 토큰 전달 방식을 검증할 최소 버튼을 만들었다. Material 3 접근성·상태 원칙을 참고한 제안이며 공식 M3 컴포넌트의 완전한 복제라고 주장하지 않는다. SEED는 의미 기반 토큰·재사용 원칙의 참고 대상이며 제품 색상·규격을 그대로 복사하지 않는다.

## 원본과 토큰 구조

| 구분 | Figma 구성 | 관리 기준 |
|---|---|---|
| Primitive | `MONIT · Primitive`, Value 모드, 18개 | 실제 색·수치. 직접 값은 이 계층에서 관리 |
| Semantic | `MONIT · Semantic`, Light/Dark, 13개 | 의미와 모드별 Primitive 별칭 |
| Component | `MONIT · Component`, Value, 9개 | 버튼 규격을 Semantic 별칭으로 참조 |
| Typography | `MONIT/Title`, `MONIT/Body`, `MONIT/Label` | 현재 Noto Sans KR 기반 초안. Android 폰트 공급·배포 방식을 별도 검토 |

승인된 디자인 값의 원본은 Figma이며 이 문서는 관리·매핑 규칙의 원본이다. 전체 색상 값 사본을 별도로 수동 유지하지 않는다. 구현 시 필요한 값은 검토한 Figma 버전에서 명시적으로 반영한다. Figma의 slash 이름은 의미 계층을 나타내며 Kotlin 식별자에 그대로 사용하지 않는다.

## Compose 매핑 제안

아래 코드 이름과 Figma의 ANDROID code syntax는 향후 구현 위치를 제안하는 메타데이터다. 실제 존재하는 Kotlin API나 Code Connect 연결을 의미하지 않는다.

| Figma 토큰·스타일 | Compose의 예정 대응 |
|---|---|
| `semantic/color/primary`, `onPrimary`, `surface`, `onSurface` | `MaterialTheme.colorScheme`의 동명 역할 |
| `semantic/color/muted`, `pressed`, `disabledBackground`, `disabledText` | 필요한 경우 `MonitTheme.colors` 확장. M3 기본 상태 표현과 비교 후 최종 확정 |
| `semantic/spacing/content`, `section`, `inline` | `MonitTheme.spacing`의 Dp 값 |
| `semantic/shape/control` | 컴포넌트 Shape 또는 radius Dp |
| `semantic/size/touchTarget` | 최소 상호작용 크기 Dp |
| `component/button/*` | `MonitButton` 내부 기본값. 기능 화면이 Primitive를 직접 참조하지 않도록 함 |
| `MONIT/Title`, `Body`, `Label` | `MaterialTheme.typography` 역할 또는 필요한 최소 확장. 크기·행간은 Sp |

Figma 치수는 설계 좌표다. Android 레이아웃은 Dp, 글자 크기·행간은 Sp로 구현하고 실제 기기의 density·fontScale에서 확인한다. 단순 px 치환으로 완료하지 않는다. Light/Dark는 동일한 의미 토큰을 사용하며 시스템 테마 선택과 동적 색상 사용 여부는 실제 앱 구현 시 확정한다.

## 버튼 상태와 접근성

- Default: 행동을 명확히 설명하는 텍스트 라벨을 사용한다.
- Pressed: 누름 상태의 시각 피드백. 현재 Figma Variant이며 자동 프로토타입 전환은 연결하지 않았다.
- Focused: 키보드 포커스를 시각적으로 구분한다. Compose에서는 실제 포커스·TalkBack 탐색을 검증한다.
- Disabled: 상호작용을 차단하고 비활성 semantics를 제공한다.
- Loading: 중복 실행을 막고 진행 중 상태를 접근성 서비스에 전달한다. 텍스트 표현 초안이며 스피너 애니메이션은 포함하지 않는다.

버튼에는 크기·여백·색·모서리·라벨의 토큰/스타일을 연결하고 텍스트 속성을 노출한다. 검토 보드는 Light/Dark 각각 다섯 상태를 인스턴스로 사용한다. 화면마다 전체 상태 Variant를 복제하지 않는다.

최소 터치 영역은 48dp를 기준으로 하며 큰 글자에서도 라벨 잘림 없이 높이가 늘어날 수 있어야 한다. 이는 [Android 접근성 기본 지침](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)을 따른다. 실제 기기·화면 크기·fontScale·TalkBack 검증은 앱 구현 후 필요하다.

## 변경과 검증 절차

1. Design이 변경 이유·대상 토큰/노드·영향받는 상태를 정리하고 필요한 승인을 확인한다.
2. Figma에서 필요한 Primitive·Semantic·Component를 갱신하고 Light/Dark 별칭·scope·미연결 값·컴포넌트 binding을 검증한다.
3. Android에 파일·노드 링크, 변경 토큰, 기준 버전과 상태 명세를 전달한다. Figma와 코드의 자동 동기화를 가정하지 않는다.
4. Android는 승인된 구현 작업에서 Theme → 공통 컴포넌트 → 기능 화면으로 적용한다. 구현체가 생긴 후에만 Code Connect를 검토한다.
5. QA가 대비·상태·터치 영역·텍스트 확대·레이아웃을 확인한다. 협업 로그에는 중요한 변경과 원본 링크만 남긴다.

## 관련 문서

- [Design 역할](roles/design.md), [Android 역할](roles/android.md), [협업 운영](workflow.md)
