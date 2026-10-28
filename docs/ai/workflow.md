# 역할별 협업 운영

## 시작과 역할 선택

하나의 MONIT 저장소와 Codex 프로젝트를 유지한다. 현재 조율 채팅은 `00 · PM`으로 사용하고 `01 · Planning`, `02 · Design`, `03 · Backend`, `04 · Android`, `05 · QA / DevOps`를 역할별 진입점으로 사용한다. 역할 채팅은 장기적인 업무 맥락을 위한 공간이며 모든 채팅을 항상 실행하지 않는다.

작업 시작 시 공통 AGENTS.md, 자신의 역할 문서, 공통 DoD를 읽고 관련 산출물만 추가 조회한다. 역할 문서는 자동 로딩·자동 동기화되지 않는다. 채팅 간에는 이전 대화 전체가 전달된다고 가정하지 않는다.

## 작업 흐름

1. Planning이 요구사항·정책·수용 기준을 정리한다.
2. PM이 담당 역할·우선순위·선행 조건을 정리하고 필요한 사용자 결정을 요청한다. 이 단계는 사용자 승인을 대신하지 않는다.
3. Design·Backend는 필요한 작업만 수행하고 UI·데이터 계약을 Android에 전달한다. 독립적인 계약이면 병행할 수 있다.
4. Android가 구현과 관련 검증을 수행하고 QA에 검토 가능한 변경을 전달한다.
5. QA는 수용 기준·회귀·CI·리뷰 지적을 검증해 PM에 결과와 위험을 전달한다.
6. 승인된 범위에서 PR을 생성·갱신하고 CodeRabbit 제안을 검증한다. 병합·릴리스는 사용자 승인 후 수행한다.
7. 작업 종료 시 관련 진행 상태와 중요한 협업 기록을 한 번에 갱신한다.

문서 수정처럼 단순한 작업은 필요한 역할만 참여한다. 실재 Issue·PR이 없으면 번호를 만들지 않으며 로컬 작업 식별자를 GitHub 번호처럼 표시하지 않는다.

## 전달 형식

```text
작업: 실제 Issue 링크 또는 로컬 작업명
From → To: 요청 역할 → 담당 역할
범위: 포함 / 제외 / 이미 받은 승인
입력: 원본 문서·파일·Figma 노드 링크와 버전
요청: 필요한 산출물·결정과 수용 기준
결과: 핵심 결론·검증 결과·산출물 링크
후속: 미확정 사항·차단 요인·필요한 승인·다음 담당
```

도구가 있다고 해서 다른 채팅에 임의로 메시지를 보내지 않는다. 사용자가 승인한 전달 대상·내용·범위에서만 메시지 도구를 사용하고 도구 성공 여부를 확인한다. 전달 권한이 없거나 도구를 사용할 수 없으면 위 형식의 인수인계 내용을 제공하고 미전달 상태를 표시한다. 역할 초기화와 실제 기능 작업 지시는 구분한다.

## 병렬 작업

- 기본은 현재 작업의 단일 담당자다. 독립 조사·리뷰·테스트 분석에만 필요한 서브에이전트를 검토한다.
- 같은 체크아웃의 같은 파일을 여러 역할이 동시에 수정하지 않는다. 읽기 전용 역할 초기화에는 Worktree를 만들지 않는다.
- 병렬 코드 수정은 승인된 기능별 브랜치·Worktree에서 수행한다. 각 작업의 기준 커밋·담당 파일을 명시하고 PM이 통합 순서를 조율한다.
- 미커밋 파일·미추적 스킬은 새 Worktree에 자동 포함된다고 가정하지 않는다. 시작 전 필요한 지침과 의존 파일의 존재를 확인한다.
- 공통 규칙·계약·공통 컴포넌트 변경은 영향을 받는 역할에 변경점과 적용 기준을 전달한다. 파일 격리는 계약 조율이나 사용자 승인을 대신하지 않는다.

## 원본과 기록

모든 Notion 작업은 **MONIT 팀스페이스**(`4072e2ce-2fe6-817b-906c-004252dfff15`)를 사용한다. 현재 상위 페이지는 [팀스페이스 홈](https://app.notion.com/p/f8d2e2ce2fe68288b2b5815be31ba2bb)이고 대시보드와 하위 기획·협업 로그를 그 아래에 둔다. 검색은 이 팀스페이스로 제한하고 생성·이동 전 parent가 이 범위에 속하는지 확인한다. 위치·접근 권한이 미확인일 때 개인 영역으로 대체하지 않는다.

현재 생성된 진입점은 [Project Dashboard](https://app.notion.com/p/4072e2ce2fe6817c9fbad344f4a902be), [Product Planning](https://app.notion.com/p/4072e2ce2fe68180a5d6c946be50c243), [AI Collaboration Log](https://app.notion.com/p/f928dd96f5144c3387034a23db0b2ec9), [Figma 초안](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=3-16)이다. 링크 접근은 서비스 권한을 따르며 저장소에 링크를 기록한 것이 외부 문서를 공개하는 것은 아니다.

| 자료 | 원본 | 다른 도구에서 관리할 내용 |
|---|---|---|
| PRD·요구사항 | Notion Product Planning | Git Issue에서 해당 요구사항 참조 |
| 로드맵·진행 현황 | Notion Project Dashboard | 현재 담당과 상태·원본 링크 |
| AI 지침·기술 ADR·Migration·데이터 계약·테스트 | Git | Notion에는 요약과 링크 |
| 승인된 디자인·토큰 명세 | Figma | Git에는 Compose 대응 및 구현 |
| 중요한 요청·답변·결정 | Notion AI Collaboration Log | 기술 근거는 Git ADR 등 원본 연결 |

Notion·Figma 초안은 승인된 제품 요구사항·시각 디자인과 구분한다. 원본 위치를 바꿀 때 기존 문서를 독립 갱신 상태로 남기지 않고 새 원본을 연결한다. 로컬 변경이 아직 푸시되지 않았다면 GitHub 링크로 내용을 확인할 수 있다고 주장하지 않는다.

Collaboration Log는 한 주제에 한 레코드를 사용한다. `Title`, `Task ID`, `From`, `To`, `Status`, `Summary`, `Reference`를 관리하며 상태는 `Open`, `Resolved`, `Blocked`다. From은 요청 역할, To는 수신 역할이며 팀원이 아닌 역할 이름을 사용한다. 여러 참조 링크는 본문에 추가한다. 긴 대화 대신 요청·답변·결정으로 요약하고 같은 주제는 갱신한다.

## 기존 공통 지침의 보존 위치

| 이전 AGENTS.md 영역 | 현재 위치 |
|---|---|
| 역할·개발 원칙 | AGENTS.md의 프로젝트·공통 원칙과 역할 진입표 |
| 아키텍처·모듈화·Kotlin/Compose 상세 | roles/android.md의 작업 범위·관련 문서 |
| 데이터·보안 | AGENTS.md 보안과 금융 데이터, roles/backend.md |
| AI 도구·Skills·비용 | AGENTS.md 개발 도구와 비용 |
| Git·PR·커밋 | AGENTS.md Git과 품질 |
| QA·완료 보고 | definition-of-done.md, roles/qa-devops.md |
| 문서·승인 | AGENTS.md 문서 원본과 승인 |

## 관련 문서

- [공통 지침](../../AGENTS.md), [공통 DoD](definition-of-done.md), [PM](roles/pm.md)
- [자동화 운영](automation.md)
- [Figma와 Compose 연동 규칙](design-system.md)
- [개발 학습 기록 운영](learning-log.md): 날짜별 실습·학습은 Dev Learning Log에 정리하고 협업 요청·결정 로그와 구분한다.
