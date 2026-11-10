# 최소 자동화 운영

## 재사용할 구성

기존 `.agents/skills`의 commit-create·pr-create·stability-analysis·write-tests를 유지한다. 역할은 업무 책임이고 스킬은 반복 작업 절차이므로 역할마다 동일한 스킬 사본을 만들지 않는다. 새 스킬은 반복되는 작업과 실패 패턴이 확인될 때만 추가한다.

사용자가 요청한 날짜별 학습 기록에는 [learning-log](../../.agents/skills/learning-log/SKILL.md)를 사용한다. 관련 증거를 수집해 기존 날짜 기록을 갱신하는 절차이며 예약 실행·무인 외부 쓰기 설정이 아니다.

Android CI의 `build` 작업은 문서 검사 후 `:core:model:test`·assembleDebug·testDebugUnitTest·lintDebug를 수행한다. 문서 검사 실패도 같은 필수 검사를 실패 처리하며 보호 규칙을 별도로 변경하지 않는다. CodeRabbit은 아래 선택적 리뷰 기준을 적용하며 필수 CI와 구분한다. 과거 PR의 성공 결과는 현재 미커밋 변경의 검증 결과가 아니다.

## 검증 선택

| 변경 | 우선 검증 |
|---|---|
| AI 지침·역할 문서·README | `pwsh -NoProfile -File scripts/Test-AiDocs.ps1`, `git diff --check`, 내용·승인 경계 검토 |
| 문서·코드 주석 | 내용·링크·diff 검토. 비동작 변경이면 전체 빌드를 강제하지 않고, 주석 문법 위험에는 필요한 컴파일 선택 |
| 금액·날짜·상태 로직 | 정상 계약·핵심 불변식의 단위 테스트부터 관련 실패·경계·중복·회귀 검증 |
| Android·Compose UI | 관련 테스트와 필요한 빌드·Lint, 화면 상태·입력·접근성 검증. 시각 확인과 동작 테스트 구분 |
| 인증·Room·RLS·동기화 | 관련 단위 테스트와 실제 저장·권한 경계의 통합 검증. 원격 실행은 승인된 환경에 한정 |
| 공통 빌드·의존성·모듈 계약 | 직접 수정한 파일뿐 아니라 영향받는 모듈의 테스트·빌드·정적 검사 |
| Figma 토큰·컴포넌트 | 별칭·모드·binding·명세 대조와 시각 검토 |
| Notion 협업 기록 | 기존 주제 조회 → 생성 또는 갱신 → 속성·상태 확인 |

문서 검사는 Git과 PowerShell 7 이상에서 실행하며 파일을 변경하지 않는다. Git으로 추적하는 AGENTS.md, docs/ai 및 .agents/skills 아래의 Markdown, 루트·하위 디렉터리의 README.md를 검사한다. 파일 목록은 Git 인덱스에서 가져오고 내용은 현재 작업 트리에서 읽으므로 기존 파일의 미커밋 수정도 검사한다. 새 문서는 stage한 뒤 검사하며, 추적되지 않는 초안·임시 파일·빌드 출력은 제외한다.

외부 URL은 네트워크로 전수 조회하지 않고 인라인 상대 링크(이미지 포함)의 대상 존재·역할 섹션·충돌 표식·후행 공백을 검사한다. 링크의 문서 내 앵커와 외부 페이지 내용은 검사하지 않는다. 참조형 링크 정의와 전체·축약 참조 표기는 지원하지 않아 오류로 처리하며 인라인 링크를 사용한다. 완전한 Markdown 파서는 아니므로 코드 예시 안의 링크 표기도 검사 대상이다. 문서 의미나 보안 검토를 대체하지 않는다.

리뷰는 변경 코드와 실제 호출 경로에서 품질·아키텍처와 동작·회귀 위험을 함께 확인한다. 상세 줄 단위 검토는 금융 무결성·동시성·권한 등 필요한 경로에 집중한다. 테스트 작성에는 기존 write-tests, 특정 경로의 안정성 분석에는 stability-analysis를 재사용하며 모든 작업에 두 스킬이나 서브에이전트를 일괄 실행하지 않는다. 중요한 테스트·보안 검증은 토큰 절약을 이유로 생략하지 않는다.

## 검증 신선도

1. 검증 직전에 `검사 명령 / 대상·입력 범위 / 기준 상태 / 결과`를 작업 기록에 남긴다. 결과는 실행 후 채운다. 입력에는 관련 제품 코드·테스트·리소스·빌드 설정·의존성을 포함하며, 환경이 결과에 영향을 주면 함께 기록한다. 별도 장부·증명서 파일은 만들지 않는다.
2. 마지막 수정 후 완료·인수인계·커밋 전에 검증 당시와 최종 입력 내용을 비교한다. 검사 도중 입력이 바뀌었거나 기준 상태를 확인할 수 없으면 기존 결과를 그대로 재사용하지 않는다.
3. 변경의 영향을 판단해 해당 검사만 재실행하고 기준 상태·결과를 갱신한다. 관련 없는 문서 수정 등으로 결과를 유지할 때는 이유를 짧게 남긴다. 영향 범위가 불명확하면 관련 검증을 넓히거나 미검증으로 보고한다.
4. 최종 보고에는 검증 이후 변경과 재검증 여부 또는 결과 유지 근거를 포함한다. 필수 검증이 남아 있으면 완료로 처리하지 않는다.

기준 상태는 다음 중 작업에 맞는 방법으로 확인한다. HEAD나 파일명 목록만 같다는 이유로 내용이 같다고 판단하지 않는다.

| 방법 | 적용과 한계 |
|---|---|
| 커밋 비교 | 깨끗한 작업 트리·CI에 적합하다. 로컬 미커밋 변경은 별도로 확인한다. CI는 실행 대상과 최종 PR 상태를 연결하며 병합 결과를 검사한 실행이면 head·base 관계도 확인한다. |
| 변경 내용 비교 | 일반 로컬 작업의 기본이다. 검증 시점의 HEAD와 `git status --short`, `git diff`, `git diff --cached`로 관련 내용을 확인하고 비교 가능한 근거를 보존한다. untracked 입력은 diff에 나오지 않으므로 내용·추가·삭제를 별도로 확인한다. |
| 관련 파일 해시 | 큰 diff·바이너리·긴 검사에서 필요할 때만 보완한다. 파일 집합의 추가·삭제와 의존 입력도 포함한다. 해시 일치는 실행 성공이나 동작 정확성의 증명이 아니다. |

부분 stage에서는 테스트한 작업 트리에 커밋에서 빠질 코드가 있을 수 있다. 실제 커밋 대상 내용과 검증한 입력의 차이를 확인하고 영향받는 검증을 적용한다. 안전하게 확인할 수 없으면 해당 커밋의 검증 한계를 명시하며, 확인을 위해 사용자 변경을 임의로 stash·삭제·롤백하지 않는다.

## 반복 실패 처리

- 실패 원인별로 `원인 가설 → 시도한 방법 → 결과·새로 얻은 정보`를 짧게 남긴다. 같은 접근으로 두 번 실패하면 원인 가설과 해결 전략을 재검토한다.
- 같은 원인에 진전 없이 세 번째 시도가 필요하면 현재 상태·검증 결과·다음 대안을 보고하고 사용자 판단을 요청하는 것을 우선한다. 전체 명령 횟수에 기계적인 제한을 두지 않는다.
- 새 원인 발견이나 실패 범위 축소 등 실제 진전이 있으면 근거를 남기고 진행할 수 있다. 접근의 이름만 바꾼 반복을 새 전략으로 취급하지 않는다.
- 중단 시 staged·unstaged·untracked를 포함한 현재 파일 상태, 마지막으로 확인된 결과, 이후 미검증 범위를 명시한다. 사용자 변경의 임의 삭제·롤백이나 검사·Hook 우회로 실패를 숨기지 않는다.

## CodeRabbit 선택적 리뷰

2026-11-10 사용자 요청에 따라 PM이 변경 영향으로 리뷰 필요 여부를 판단한다. 파일 수·확장자만으로 결정하지 않으며, 판단은 현재 PR head를 기준으로 본문의 참고 사항에 `CodeRabbit: 요청 / 생략 · 대상 head · 이유 · 관련 검증`으로 남긴다. 필요성 판단을 매번 사용자에게 재승인받지 않는다. PM 부재나 영향 불명확은 자동 생략 사유가 아니다.

| 판단 | 대표 변경 |
|---|---|
| 요청 | 금융 계산·저장/복구·동기화·인증/권한·민감 데이터·동시성, 주요 앱 동작·공통 아키텍처, 빌드/배포/CI·보호 규칙처럼 실패 영향이 큰 설정 |
| 생략 가능 | 의미·동작이 바뀌지 않는 오타·문서 표현·링크·단순 정렬, 이미 검증한 결과 기록 등 PM이 영향 범위와 근거를 확인한 변경 |

- `.coderabbit.yaml`의 `reviews.auto_review.enabled`와 `auto_incremental_review`를 `false`로 두고, 필요한 PR에만 담당자가 `@coderabbitai review` 댓글로 요청한다. 새 push마다 자동 재실행하지 않으며 변경된 head의 영향에 따라 다시 요청한다. 전체 재검토가 필요한 경우에만 `@coderabbitai full review`를 사용한다. 추가 과금 옵션은 사용하지 않는다.
- 이미 자동 리뷰 중인 PR을 생략하기로 판단하면 `@coderabbitai pause`만 담은 댓글로 이후 자동 리뷰를 중지 요청한다. 전송만으로 진행 중인 실행이 즉시 취소되었다고 보고하지 않으며 실제 응답·상태를 확인한다. 이미 나온 지적은 읽고 필요한 조치를 유지한다.
- main 보호 규칙의 필수 검사에서는 CodeRabbit만 제외한다. Android CI의 `build`, 최신 base 요구, 관리자 적용 및 PR 보호 등 다른 설정은 유지한다. CodeRabbit을 요청한 PR은 PM·QA가 실제 리뷰와 필요한 수정·재검증을 확인한 뒤에만 사용자에게 병합을 요청한다. 이 조건은 GitHub의 필수 검사로 자동 강제되는 것이 아니므로 PR 기록과 최종 검토로 확인한다.
- 리뷰 생략은 테스트·보안 검증·로컬 변경 검토 생략이나 사용자 병합 승인 대체가 아니다. 새 변경·rebase 충돌 해결·새 위험이 있으면 판단을 갱신한다. 기존 미해결 지적이나 실패를 없애기 위해 생략으로 바꾸지 않는다.
- 정책 파일은 main 병합 후 공통 기준이 된다. 반영 전 열린 PR에는 기존 자동 리뷰가 남을 수 있으므로 PR별 상태와 중지 요청을 구분한다. 보호 규칙의 원격 변경과 파일의 PR 반영도 별도로 확인한다.

공식 근거: [자동 리뷰 설정](https://docs.coderabbit.ai/reference/configuration), [리뷰 요청·일시 중지 명령](https://docs.coderabbit.ai/reference/review-commands).

## 출력 관리

빌드·테스트·설치처럼 출력이 긴 명령은 전체 출력을 임시 로그에 저장하고, 실행 직후 실제 종료 코드를 별도로 보존한다. 필터·파이프라인의 성공을 원래 명령의 성공으로 취급하지 않는다. 대화에는 검사 범위·종료 코드·성공/실패 개수와 필요한 오류 주변 구간만 가져오고 추가 진단 때 해당 구간을 넓힌다. 로그에도 인증 정보·실제 금융 데이터를 남기지 않으며 임시 로그를 Git에 포함하지 않는다. 짧은 상태 조회까지 파일 저장을 강제하지 않는다.

## Codex와 플러그인 운영

2026-11-10 `monit-codex-kit` 적용 시 확인한 상태다. 전역에 지침 사본을 만들지 않고 MONIT의 기존 규칙·스킬·승인 경계를 유지한다.

### 설정과 자동 압축

- 확인한 CLI는 `0.167.0-alpha.3.2`다. 사용자 기본 모델은 `gpt-6.1-sol`이지만 현재 PM 채팅 메타데이터는 `gpt-6-astra`로, 기본값과 채팅 선택값을 구분한다. 이번 작업에서는 모델·추론 수준을 변경하지 않았다.
- 공식 설정의 `model_auto_compact_token_limit`은 지원되며 미설정 시 모델 기본값을 사용한다. 사용자 설정과 프로젝트 상위 경로에서 명시적인 압축 한도·문맥 크기 재정의는 발견하지 않았다. 프로젝트 `.codex/config.toml`은 없으며 MONIT은 이미 신뢰 등록되어 있다.
- 모델 캐시의 기본 `context_window`는 Astra 272,000, Sol 500,000이고 `effective_context_window_percent`는 95다. 별도 `max_context_window` 872,000은 현재 채팅에서 그 한도가 활성화됐다는 증거가 아니다. 실제 세션의 유효 압축 기준은 확인하지 못했다.
- 따라서 **400,000 설정은 보류하고 기존 모델 기본값을 유지**한다. Astra의 기본 문맥 크기는 400K보다 작고, 다른 모델도 현재 유효 압축 기준이 400K보다 높다는 확인이 필요하다. 조건이 충족될 때만 프로젝트 `.codex/config.toml` 최상위에 시험값을 넣는다. 더 낮은 기존 기준을 올리거나 `model_context_window`를 부풀리지 않고 `compact_prompt`·권한·승인 설정도 바꾸지 않는다.
- 설정은 CLI 인자·선택된 프로필·프로젝트·사용자·기본값 등 적용 계층을 함께 확인한다. 프로젝트 설정은 신뢰된 프로젝트에서만 로드되므로 파일 저장만으로 적용됐다고 보고하지 않는다. 변경 후 새 세션에서 확인하며 검증하려고 문맥을 일부러 채우지 않는다.

### 설치 출처와 재현

| 플러그인 | 확인한 기존 설치 | 범위·구성 |
|---|---|---|
| [EGM](https://github.com/google/elephant-goldfish-model) | `egm@egm` 0.1.1 · `6d2af49f635515a472ce0dbba375c62b73dace26` | 사용자 Codex 플러그인, 활성화. 스킬 7개·검토자 10개·문서 기준 등 리소스 포함 |
| [Paperthin](https://github.com/LilMGenius/paperthin) | `paperthin@paperthin` 1.0.0 · `1306ea4a2c038b5dee508db23197b3a19ccfbdfc` | 사용자 Codex 플러그인, 활성화. 같은 설치 커밋의 공식 manifest에 스킬 45개 등록 |

두 설치의 Git 작업 트리는 깨끗하고 전역 skills·프로젝트 `.agents/skills`에 중복 사본이 없다. 기존 설치를 재사용하여 재설치·업그레이드를 수행하지 않았다. 버전 문자열이 같아도 커밋은 다를 수 있으므로 함께 확인한다. 캐시·인증 정보·기기별 절대 경로를 저장소에 복사하지 않는다.

다른 환경에서는 먼저 `codex plugin list --json`으로 각 플러그인의 설치 여부·활성화 상태를 확인하고, marketplace 등록 출처·ref도 별도로 대조한다. 조회가 실패하거나 상태가 불명확하면 설치하지 않는다. 이미 설치된 플러그인은 캐시·사용자 수정·활성화 상태를 보존하고 해당 설치 블록을 건너뛴다.

아래는 해당 플러그인과 marketplace 등록이 모두 없는 환경의 예제다. `--ref`는 위 확인 커밋을 선택한다. 이미 등록된 marketplace는 출처·ref를 확인한 뒤 등록 단계를 제외한 별도 절차를 검토하며, 실패를 무시하거나 기존 등록을 임의로 교체하지 않는다. 필요한 플러그인의 블록만 전체 실행하고, 오류 뒤의 줄만 따로 실행하지 않는다.

EGM 설치:

```powershell
& {
codex plugin marketplace add google/elephant-goldfish-model --ref 6d2af49f635515a472ce0dbba375c62b73dace26
if ($LASTEXITCODE -ne 0) { throw 'EGM marketplace registration failed; stop installation.' }
codex plugin add egm@egm
if ($LASTEXITCODE -ne 0) { throw 'EGM installation failed; stop installation.' }
codex plugin list --json --marketplace egm
if ($LASTEXITCODE -ne 0) { throw 'EGM verification failed; inspect installation state before retry.' }
}
```

Paperthin 설치:

```powershell
& {
codex plugin marketplace add LilMGenius/paperthin --ref 1306ea4a2c038b5dee508db23197b3a19ccfbdfc
if ($LASTEXITCODE -ne 0) { throw 'Paperthin marketplace registration failed; stop installation.' }
codex plugin add paperthin@paperthin
if ($LASTEXITCODE -ne 0) { throw 'Paperthin installation failed; stop installation.' }
codex plugin list --json --marketplace paperthin
if ($LASTEXITCODE -ne 0) { throw 'Paperthin verification failed; inspect installation state before retry.' }
}
```

한 플러그인의 설치·확인 실패가 다른 플러그인의 재설치를 뜻하지 않는다. 실패 후에는 해당 플러그인의 등록·설치·활성화 상태를 다시 확인하고 남은 단계만 별도 절차로 정한다. 이미 성공한 설치 블록을 반복하거나 확인 실패를 설치되지 않았다는 증거로 사용하지 않는다. 목록 조회 성공도 실제 스킬 실행 성공과는 구분한다.

지원되는 `[plugins."egm@egm"]`·`[plugins."paperthin@paperthin"]`의 `enabled = true`는 기존 사용자 설정에 이미 있어 프로젝트에 중복 설정하지 않았다. 설치·활성화가 바뀌면 데스크톱 앱을 재시작하고 새 채팅의 스킬 선택기에서 확인한다. 이번에는 설치·설정 변경이 없어 재시작을 필수로 요구하지 않는다. 현재 제공된 스킬 목록에 EGM 7개와 아래 Paperthin 선택 항목이 보이지만, 모든 설치 항목의 선택기 표시·실행을 시험한 것은 아니다.

### 선택할 이름과 호출 기준

첨부 Claude용 표의 EGM 7개·Paperthin 45개 이름은 설치본의 이름과 일치한다. Claude의 `/egm:...` 문법을 옮기지 않고 Codex 스킬 선택기에서 실제 항목을 선택한다. 호스트가 플러그인 접두사를 표시하면 `egm:egm-review`, `paperthin:readback`처럼 소속도 확인한다.

| 선택할 스킬 | 필요한 경우 |
|---|---|
| `egm-brainstorm` → `egm-draft` → `egm-review` | 사용자 요청에 따라 요구사항 구체화·설계 작성·독립 검토 중 필요한 단계만 선택 |
| `egm-bootstrap`, `egm-drift-report`, `egm-resolve`, `egm-review-loop` | 각각 디렉터리 안내, 설계와 구현 차이, 지적 해결, 제한된 반복 검토가 필요할 때 선택 |
| `readback` | 중요한 요청 해석이 모호할 때 확인; 명확한 작은 요청에는 추가 질문을 강제하지 않음 |
| `shower` | 이전 설명 없이도 문서를 이해할 수 있는지 새 맥락에서 검토 |
| `factchk` | 중요한 기술 주장·수치·인용의 근거 확인 |
| `re0-memo` | 완료·실패에서 다음 작업에 필요한 교훈만 기존 기록에 반영 |
| `re0-workflow` | 실제로 여러 스킬의 순서·의존성을 조율할 필요가 있을 때 선택 |

EGM 7개는 모두 frontmatter의 `disable-model-invocation: true`를 유지한다. 확인한 CLI `0.167.0-alpha.3.2`의 [frontmatter 파서](https://github.com/openai/codex/blob/rust-v0.167.0-alpha.3.2/codex-rs/skills/src/parser.rs)는 이 필드를 읽지 않으며, 이 설치의 `agents/openai.yaml`에도 `allow_implicit_invocation`이 없어 스킬이 모델에 노출되고 자동 선택될 수 있다. MONIT의 사용자 직접 호출 전용 방침은 에이전트가 따라야 할 공통 지침이며 호스트가 메타데이터로 자동 호출을 차단했다는 의미가 아니다. Paperthin의 사용자 호출 전용 항목은 frontmatter와 `agents/openai.yaml`의 `allow_implicit_invocation: false`가 대응한다. 메타데이터를 완화하거나 사본으로 우회하지 않는다.

자동 호출 가능은 실행 의무나 권한 부여가 아니다. 기존 MONIT의 커밋·PR·테스트·학습 스킬을 우선하며 작은 작업에 전체 플러그인 절차를 붙이지 않는다. `modelchk`는 조언이며 실제 모델 자동 전환기가 아니다. 새 맥락이 필요한 검토는 실제 격리된 검토 환경을 확인한다. `re0-work`·`re0-loop`·`re0-squash`는 대화 압축 명령이 아니고, `re0-squash`는 일반 PR squash merge도 아니다. 설치 확인용으로 재구축·다중 검토·회고·업그레이드·릴리스 절차를 실행하지 않는다. 자동 업데이트 예약, 다른 에이전트 설치, Spotify Portal·shunt는 추가하지 않는다.

지원 근거: [Codex 설정 참조](https://learn.chatgpt.com/docs/config-file/config-reference), [설정 계층](https://learn.chatgpt.com/docs/config-file/config-basic), [플러그인 설치·활성화](https://developers.openai.com/plugins/build/plugins). 등록·필수 파일 확인과 실제 스킬 실행 성공은 구분한다.

## Hooks와 CI

Git Hooks는 Git 명령 시점, Codex Hooks는 에이전트 생명주기에 동작한다. 둘을 같은 설정으로 취급하지 않는다. Hook은 지원 여부뿐 아니라 실제 설정·신뢰·실행 결과를 확인해야 활성화됐다고 보고한다.

현재 최소 구성은 수동 실행 가능한 문서 검사와 기존 Android CI를 재사용한다. 사용자 전역 설정·core.hooksPath·Codex 신뢰 설정을 변경하지 않는다. 반복적인 검사 누락이 확인되면 이 검사를 저장소 Hook 또는 CI에 연결하는 별도 변경안을 만든다. 자동 Hook 설치·채팅 간 무인 전달·릴리스 자동 실행은 이번 기본 구성에 포함하지 않는다.

## MCP와 비용

- GitHub는 MONIT 저장소에 한정한다. 설정·병합·릴리스는 별도 승인 경계를 유지한다.
- Notion은 관련 페이지와 협업 주제만 조회한다. 앱 도구와 독립 CLI의 인증 상태를 구분한다.
- Figma는 대상 파일·노드와 필요한 토큰만 읽는다. 일반 편집과 AI 크레딧 소비 기능을 구분하고 후자는 임의 실행하지 않는다.
- Supabase 읽기 전용 연결로 쓰기를 시도하지 않는다. 원격 적용은 별도 승인된 실행 경로가 필요하다.
- Ollama는 기존 로컬 모델·실행 스크립트를 재사용한다. 연결 확인 전에 서버 정상·모델 가용을 가정하지 않는다. 추가 모델 다운로드·서비스 실행을 조직 구축의 필수 단계로 취급하지 않는다.
- 권한·요금제·한도를 확인할 수 없는 부분은 미확인으로 남긴다. 추가 구독·크레딧·별도 API 과금은 활성화하지 않는다.

## 관련 문서

- [협업 운영](workflow.md), [공통 DoD](definition-of-done.md)
- [README와 실행 명령](../../README.md), [Android CI](../../.github/workflows/android-ci.yml)
