---
name: commit-create
description: Analyze approved MONIT Git changes and create logically separated local commits with Korean messages. Use when the user requests commits; does not push, open PRs, or rewrite history.
---

# Commit Create

승인된 MONIT 변경을 분석해 논리적 단위의 로컬 커밋을 만든다. 인자는 호출 메시지의 자연어 지시에서 읽는다. 사용자 지시와 저장소의 AGENTS.md를 먼저 적용한다.

## 범위와 사전 확인

- 현재 Git 루트와 origin이 `1971123-seongmin/MONIT`인지 확인한다. URL에 인증정보가 있으면 출력하지 않는다. 저장소를 확인할 수 없으면 변경하지 않고 확인을 요청한다.
- 현재 브랜치, staged·unstaged·untracked 상태와 최근 커밋을 확인한다. untracked 파일은 `git diff`에 나오지 않으므로 포함 후보의 내용도 별도로 읽는다.
- 사용자·다른 작업의 변경과 이번 승인 범위를 구분한다. 기존 staged 변경을 자동으로 해제하거나 함께 커밋하지 않는다. 안전하게 분리할 수 없으면 커밋을 보류하고 필요한 확인만 요청한다.
- 전역 Git 설정, 다른 저장소, 원격 서비스 설정을 변경하지 않는다.

읽기 예시:

```sh
git status --short
git branch --show-current
git diff
git diff --cached
git log -5 --oneline
```

## 메시지

MONIT의 커밋 형식은 Conventional Commits 스타일의 프로젝트 규칙이다. Angular 규칙을 그대로 준수한다고 표현하지 않는다.
PR 제목은 `[KEYWORD/#이슈번호] 이슈명 또는 적절한 문장/단어` 형식이며 커밋 메시지와 구분한다. 예를 들어 `[FEAT/#3] 홈 화면 구현`을 커밋 제목으로 그대로 복사하지 않는다. squash 병합 시 최종 커밋 제목은 `feat(home): 홈 화면을 구현한다`처럼 아래 커밋 형식으로 별도 작성한다. 이 스킬은 병합을 실행하지 않는다.

```text
<type>(<scope>): <subject>

<body>

<footer>
```

- 허용 type은 AGENTS.md를 기준으로 한다. 현재 `feat`, `fix`, `mod`, `refactor`, `test`, `docs`, `style`, `perf`, `chore`, `ci`이다.
- scope는 필수이며 기존 저장소 관례에 맞는 영문 식별자를 사용한다. 특정 기능 영역이 없으면 변경 목적에 맞는 공통 scope(예: rules, setup)를 선택한다.
- 제목과 본문은 한국어로 쓴다. 제목은 간결한 현재형 문장으로 쓰고 마침표를 붙이지 않는다.
- 본문은 변경 이유·영향·주의사항을 설명할 필요가 있을 때만 쓴다. 의미 없는 본문을 강제하지 않는다.
- 실제 변경과 다른 기능·검증 결과를 쓰지 않으며, 에이전트 귀속 문구를 추가하지 않는다.

```text
fix(routines): 월말 보정으로 납입 항목 중복 생성을 방지한다

반복 규칙의 원래 납입일과 월별 보정일을 구분해 동일 월의 항목이 중복 생성되지 않도록 한다.
```

이 예시는 메시지 형식이며 구현된 기능이나 확정 요구사항을 뜻하지 않는다.

## 분리와 실행

1. 변경 목적과 의존성을 기준으로 커밋 순서를 정한다. 독립적인 기능·수정·정리는 분리하되, 하나의 기능과 테스트 또는 API와 구현은 함께 유지한다. 모듈이 다르다는 이유만으로 쪼개지 않는다.
2. 승인된 파일·hunk만 명시적으로 stage한다. `git add .` 또는 `git add -A`로 전체 변경을 무조건 포함하지 않는다.
3. 각 커밋 전에 `git diff --cached --check`와 staged diff를 확인한다. 관련 없는 변경, 비밀정보, 실제 금융 데이터, 로컬 SDK 경로가 없어야 한다.
4. [검증 신선도 절차](../../../docs/ai/automation.md#검증-신선도)에 따라 검증 결과가 실제 커밋 대상에 유효한지 확인한다. 부분 stage에서는 테스트한 작업 트리와 staged 내용의 차이 및 빠진 변경의 영향을 확인한다. 필수 검증을 확인할 수 없으면 커밋을 보류하고 한계를 보고한다. 커밋 요청만으로 무관한 코드 수정이나 전체 테스트를 추가하지 않는다.
5. 확인한 staged 변경으로 커밋한다. 실패 시 [반복 실패 처리](../../../docs/ai/automation.md#반복-실패-처리)를 적용하고 hook·검증을 우회하지 않는다.
6. 커밋 해시·메시지·포함 변경과 남은 staged·unstaged·untracked 변경을 확인해 한국어로 보고한다.

## 경계

이 스킬은 로컬 커밋까지 담당한다. push·PR 생성은 별도로 승인된 작업 흐름에서 처리하며, PR 병합·배포 권한으로 확대하지 않는다.

소스 수정, 사용자 변경 삭제·덮어쓰기, `reset --hard`, `clean -fd`를 수행하지 않는다. 기존 커밋 재작성, `--amend`, `--no-verify`, 강제 push는 이 스킬의 기본 범위에 포함하지 않는다.
