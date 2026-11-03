# MONIT

매월 반복되는 개인 금융 일정을 관리하는 Android 앱입니다. AI 에이전트 기반 개발과 자동화를 함께 실습합니다.

현재 앱은 Kotlin·Jetpack Compose 기반 실행 확인용 임시 화면입니다. Version Catalog와 Compose 최소 런처까지 적용했으며, 제품 디자인 시스템과 기능 화면은 이후 구현합니다.
`core:model`에는 원화 금액과 날짜의 순수 Kotlin 검증·계산 및 단위 테스트를 추가했습니다. 실제 거래·잔액 저장은 아직 구현하지 않았습니다.

현재는 서버 연결 없이 로컬 MVP의 기반을 구현하는 단계입니다. 아래 개발 범위에는 초기 장기 목표가 포함되어 있습니다.

## 개발 범위

- 반복 납입 일정과 월별 수동 완료 이력
- 월간 대시보드와 예산
- 사전·당일·미완료 알림
- Supabase 인증과 사용자 데이터 동기화

은행 연결, 자동 송금, 거래 자동 수집, 카드 실적·혜택 관리는 제외합니다.

## 프로젝트 구조

- `app`: 앱 진입점과 의존성 조립
- `core:model`: Android 비의존 Kotlin 금액·날짜 검증과 계산

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

`core:model:test`는 금액·날짜 단위 테스트를 실행합니다. 앱 테스트 소스는 아직 없어 `testDebugUnitTest`의 `NO-SOURCE`를 앱 동작 테스트 통과로 해석하지 않습니다.

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
Room과 Supabase의 동기화·충돌 정책을 확정한 뒤 데이터 계층을 구현합니다.

## 보안과 비용

실제 금융 데이터, 비밀키, 인증 토큰, 서명키, 로컬 SDK 경로를 커밋하지 않습니다.
Supabase 서버 비밀키를 Android에 넣지 않으며 사용자 데이터에는 RLS를 적용합니다.
기존 이용 권한과 무료 플랜을 우선하고 추가 구독·크레딧·별도 과금 API는 사용자 승인 없이 활성화하지 않습니다.
