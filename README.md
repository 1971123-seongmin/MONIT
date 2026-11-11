# MONIT

매일 수입·지출·이체를 기록하고, 월별 납입 여부와 저축·투자 현황을 확인하는 Android 앱입니다. AI 에이전트 기반 개발과 자동화를 함께 실습합니다.

현재 앱은 Kotlin·Jetpack Compose 기반 실행 확인용 임시 화면입니다. Version Catalog 공통 설정과 Hilt 기반을 사용합니다.
`core:designsystem`에는 Figma 기반 색상·타이포·간격·shape와 공통 컴포넌트가 있습니다. 앱 테마는 기기 설정의 라이트·다크를 따르며 동적 색상은 사용하지 않습니다. 제품 기능 화면은 이후 구현합니다. [디자인 시스템 사용 계약](core/designsystem/README.md)을 참고합니다.
`core:model`에는 원화 금액·날짜 검증과 계산, 계좌·거래 값과 Repository 계약이 있습니다.
`core:data`는 Room 기반 계좌·거래 신규 저장, 일관된 조회, 같은 요청의 재시도 복구를 구현하고 Hilt로 연결합니다. 앱 화면에서는 아직 이 저장 API를 호출하지 않습니다. [저장 범위와 사용 계약](core/data/README.md)을 참고합니다.
Navigation 2의 공통 실행기·앱 이동 정책·feature별 타입 경로를 구현했습니다. 실제 제품 화면과 MainActivity 연결은 후속 단계입니다. [탐색 사용 계약](core/navigation/README.md)과 [전체 설계·후속 순서](docs/architecture/navigation2-navigation-plan.md)를 참고합니다.

현재는 서버 연결 없이 단일 기기에 저장하는 로컬 MVP의 기반을 구현하는 단계입니다. Figma 설계 완료와 앱 기능 구현 완료는 구분합니다.

## 개발 범위

- KRW 수입·지출·내부 이체 기록, 월간 달력·목록·대시보드
- 계좌와 기준 잔액, 월간 저축·투자 납입 현황
- 반복 납입 일정·알림과 사용자가 확인하는 월별 완료 이력
- 기존 기록의 지원 유형 이관, 파일 백업·복원

위 목록은 목표 범위이며 모두 구현된 상태가 아닙니다. 예산·Supabase 인증·다중 기기 동기화는 현재 로컬 MVP 범위에 포함하지 않습니다.

은행 연결, 자동 송금, 거래 자동 수집, 카드 실적·혜택 관리는 제외합니다.

## 디자인과 구현 상태

[Figma 최신 디자인](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=123-2)에서 디자인 시스템, 와이어프레임, 기능별 화면과 상태, 개발 전달 자료를 확인할 수 있습니다. MONIT의 기록·조회 흐름에 맞춘 구성으로, 2026-11-10 개편 이전 자료는 Archive에 보존했습니다.

| 구분 | 현재 상태 |
|---|---|
| Figma | 디자인 시스템과 기능별 화면 설계 개편 완료. 시각 설계 원본이며 앱 구현·실기기 검증 완료를 뜻하지 않음 |
| Compose 디자인 시스템 | 개편 19개 family의 공통 API와 상태별 Preview, 선택적 클릭 Modifier 구현. 호스트 시험과 실제 Prevue·실기기 검증 범위는 디자인 시스템 README에서 구분 |
| 제품 화면 | main의 앱은 실행 확인용 화면. Figma의 달력·입력·납입 화면이 앱에 모두 연결된 상태가 아님 |

Material 3와 SEED의 의미·상태 원칙을 적용하며, 기존 파란색과 기기 설정에 따른 Light/Dark를 유지합니다. 동적 색상은 사용하지 않습니다. 최신 페이지별 링크, 구현된 컴포넌트의 사용 경계와 검증 방법은 [디자인 시스템 README](core/designsystem/README.md)에서 확인합니다.

## 프로젝트 구조

- `app`: 앱 진입점과 의존성 조립
- `core:common`: [CoroutineContextProvider·CoroutineScopeProvider의 Hilt 제공](core/common/README.md)
- `core:model`: Android 비의존 Kotlin 값·금액·날짜 계산과 Repository 계약
- `core:data`: Room Entity·DAO·Repository 구현과 Hilt 바인딩, schema v1
- `core:designsystem`: Material 3 기반 MONIT 테마·공통 컴포넌트·상태 Preview
- `core:navigation`: UI 수명의 탐색·Back·결과 반환 실행기
- `feature:home`·`feature:transactions`: 홈·거래 타입 경로와 화면 등록 계약. 제품 UI는 후속 단계
- `feature:accounts`: 계좌 등록 ViewModel·상태와 계좌·등록 타입 경로

기능 모듈은 실제 구현에 맞춰 추가합니다. 공통 개발 규칙은 [AGENTS.md](AGENTS.md)를 따릅니다.

## 빌드와 검증

Android Studio의 JDK와 Android SDK를 준비합니다. 현재 빌드 설정은 SDK 36과 최소 SDK 26을 사용합니다.
로컬 SDK 위치는 Git에서 제외되는 `local.properties`로 설정합니다.

```powershell
.\gradlew.bat :core:model:test assembleDebug testDebugUnitTest lintDebug
```

Linux와 CI에서는 다음 명령을 사용합니다.

```sh
bash ./gradlew :core:model:test assembleDebug testDebugUnitTest lintDebug
```

`core:model:test`는 금액·날짜·저장 값 계약의 단위 테스트를 실행합니다. `testDebugUnitTest`에는 `core:data`의 Robolectric 파일 SQLite 통합 테스트, `core:navigation`의 실제 NavController 수명·결과 반환 테스트, 앱의 Compose 탐색 정책 테스트가 포함됩니다. 탐색 시험의 테스트 전용 화면은 실제 제품 UI 검증을 대신하지 않습니다.
`core:designsystem:testDebugUnitTest`는 Compose 상호작용·semantics·글자 확대와 호스트 렌더를 검증합니다. 렌더 산출물은 `core/designsystem/build/reports/designsystem/screenshots/testDebugUnitTest`에 생성하며 실기기 스크린샷과 구분합니다.
기기/에뮬레이터에서의 실행과 전원 손실 내구성은 별도 검증 대상입니다.

## 로컬 LLM

Ollama 서버와 Codex CLI를 설치한 뒤 프로젝트 루트에서 실행합니다.

```powershell
ollama pull qwen3:8b
ollama create monit-local -f tools/ollama/Modelfile
.\scripts\Start-LocalCodex.ps1
```

`monit-local:latest`는 qwen3:8b를 기반으로 문맥 길이를 32768로 설정한 로컬 별칭입니다.
이 모델은 학습·보조 작업용 초기 후보이며, 코드 변경 결과는 빌드와 테스트로 검증합니다.

## 외부 연결

- GitHub Actions: `.github/workflows/android-ci.yml`
- CodeRabbit 한국어 리뷰: `.coderabbit.yaml`; GitHub 앱 설치가 별도로 필요합니다.
- Figma MCP 공식 주소: `https://mcp.figma.com/mcp`
- Notion MCP 공식 주소: `https://mcp.notion.com/mcp`

Codex 앱 연결과 독립 CLI의 OAuth 인증 상태는 각각 확인합니다.
등록된 CLI 서버는 `codex mcp list`로 확인하며, 재인증은 `codex mcp login figma` 또는 `codex mcp login notion`으로 진행합니다.

Supabase 클라이언트 설정 키 이름은 `.env.example`을 참고합니다. 현재 Android 앱은 이 파일을 자동으로 읽거나 Supabase에 연결하지 않습니다.
현재 Room은 로컬 저장만 담당합니다. Supabase 연동은 서버 명세와 동기화·충돌 정책을 확정한 뒤 구현합니다.

## 보안과 비용

실제 금융 데이터, 비밀키, 인증 토큰, 서명키, 로컬 SDK 경로를 커밋하지 않습니다.
Supabase 서버 비밀키를 Android에 넣지 않으며 사용자 데이터에는 RLS를 적용합니다.
기존 이용 권한과 무료 플랜을 우선하고 추가 구독·크레딧·별도 과금 API는 사용자 승인 없이 활성화하지 않습니다.
