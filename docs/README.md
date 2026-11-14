# 문서 안내와 구현·검증 상태

## 확인 기준

2026-11-14 23:30 KST 상태 기록이다. 제품 기준은 `dd16d95122400ea2ef3a1be593a41f8e5592b854`, 기존 문서 PR #75를 포함한 main은 `8de243d64b5439bc9b1bc55abe5d503eebf66093`이다. [main CI 43540272865](https://github.com/1971123-seongmin/MONIT/actions/runs/43540272865)는 해당 main에서 성공했다. 아래 PR #73의 후보 검증은 main 반영·출시 완료와 구분하며 이후 변경의 결과를 대신하지 않는다.

기술 계약·구현·검증 절차의 원본은 Git이다. 제품 요구사항과 진행 기록은 MONIT 팀스페이스의 Notion, 승인된 시각 설계는 Figma에서 관리한다. 외부 진입점과 원본 경계는 [협업 운영](ai/workflow.md#원본과-기록)을 따른다. 이번에는 기존 대시보드·기획과 Figma 인계 문구만 좁게 갱신했으며 전체 디자인을 재검증하지 않았다. [Day6 학습 기록](https://app.notion.com/p/4182e2ce2fe681759e14c0805dc6e001)은 Day5 이후의 검증·실패·선택 이유를 정리한다.

## main에 반영된 범위

현재는 서버 연결 없이 단일 기기에 저장하는 로컬 MVP다. 구현된 경로와 출시 검증 완료를 구분한다.

| 범위 | 현재 구현과 사용 계약 |
|---|---|
| 저장·제출 복구 | Room schema 9, 비파괴 1→9 Migration, 원래 요청·영수증·명시 재시도·선택 종료. [저장 계약](../core/data/README.md) |
| 입력·초안 | 거래 수입/지출/이체·계좌 일반/저축/투자 등록, Room 미제출 초안의 명시 복구. [입력 계약](../feature/transactions/ENTRY.md), [계좌 상태](../feature/accounts/STATE.md) |
| 거래 변경 | 수정·논리 삭제·변경 이력·확정 revision 충돌. [거래 상태](../feature/transactions/STATE.md) |
| 납입·알림 | 월별 규칙·수동 확인·연결/해제·건너뜀·재확인, 사용자 알림 선호·OS 재조정. [납입 상태](../feature/home/STATE.md), [알림 계약](../feature/home/REMINDERS.md) |
| 기준액·자산 | 기준일 마감액의 영향 검토·명시 확정·이력, 확인된 장부 잔액과 선택 월 순납입. [계좌·자산 계약](../feature/accounts/ASSETS.md) |
| 백업·복원 | 암호화 `.monitbak`, 논리 v3 작성·strict v1/v2 읽기, 현재 Room의 원자 전체 교체·새 세대·복원 근거와 fence. [백업 계약](../core/data/BACKUP.md) |
| 이관 | 지원 JSON의 검토·명시 판단·검증된 같은 revision 백업 뒤 원자 반영·출처 대조. 합성 자료로 구현·시험했으며 실제 자료 이관은 승인 후 실행 조건 확인 중. [입력 형식](contracts/import-preview-v1.md), [writer 계약](architecture/import-writer-plan.md) |

관련 [#48](https://github.com/1971123-seongmin/MONIT/pull/48)·[#50](https://github.com/1971123-seongmin/MONIT/pull/50)·[#51](https://github.com/1971123-seongmin/MONIT/pull/51)·[#53](https://github.com/1971123-seongmin/MONIT/pull/53)·[#55](https://github.com/1971123-seongmin/MONIT/pull/55)·[#57](https://github.com/1971123-seongmin/MONIT/pull/57)·[#59](https://github.com/1971123-seongmin/MONIT/pull/59)·[#61](https://github.com/1971123-seongmin/MONIT/pull/61)·[#63](https://github.com/1971123-seongmin/MONIT/pull/63)·[#65](https://github.com/1971123-seongmin/MONIT/pull/65)·[#67](https://github.com/1971123-seongmin/MONIT/pull/67)·[#69](https://github.com/1971123-seongmin/MONIT/pull/69)·[#71](https://github.com/1971123-seongmin/MONIT/pull/71)은 병합됐다.

예산·Supabase 인증/동기화는 현재 로컬 MVP에 포함하지 않는다. 은행 연결·자동 송금·거래 자동 수집·카드 실적/혜택도 제외한다. 장부 잔액은 은행 잔액·시장 평가액·수익률을 뜻하지 않는다.

## 검증과 남은 일

- PM/QA 인계 기준으로 #71의 S23 지정 수용시험은 PASS다. 원래 QA 자료와 초안은 보존됐다. 지정 시험의 통과를 전체 앱·성능·장애 검증 완료로 확대하지 않는다. [당시 host 결과와 APK 기록](qa/s23-acceptance-fixes.md)을 함께 확인한다.
- [PR #73](https://github.com/1971123-seongmin/MONIT/pull/73)은 위 확인 시점에 **OPEN·미병합**이다. head `5319f195ede151ae15caa178302a026f0e455937`의 [CI 43603796073](https://github.com/1971123-seongmin/MONIT/actions/runs/43603796073) 필수 5개가 성공했고 PM이 88 XML·716시험의 실패/오류/skip 0과 동결 APK hash를 확인했다. API26 첫 검사에서는 Hilt 수신기/Application 불일치로 프로세스가 종료됐다. 해당 실패를 확인한 뒤 S23 새 APK 설치·smoke·장애·20회 측정은 시작하지 않았다. 실제 trigger action은 미확정이며 CI 성공을 기기 PASS로 기록하지 않는다.
- 이전 `b788045` 후보의 S23 복원 smoke는 73.269MiB로 기존 64MiB 기준을 초과했다. 새 후보는 해당 기준의 실측 결과가 아직 없다. 이전 후보의 20회 시간 결과·최고 메모리·호환성 성공을 새 head에 옮기지 않는다. 측정 baseline·전체 window·5ms 간격을 유지하고 강제 GC·한도 상향·측정 부담 차감으로 실패를 해소하지 않는다.
- KDF 20회 p95, 상한 payload의 시간·peak heap, 복원 성능·장애·알림의 남은 기기 검증이 있어 **출시 준비는 미완료**다. [백업 host 검증의 미측정 항목](qa/backup-restore-host.md), [후속 검증 Issue #72](https://github.com/1971123-seongmin/MONIT/issues/72)를 따른다.
- 기기 검증은 사용자 승인 범위에서 재개했다. 실제 기기는 `SM-S911N`이며 최소 지원 API26 에뮬레이터도 설치·검증 승인을 받았다. 기존 `.qa`와 복원 전용 `.qa.backup`, #73의 `.qa.performance`는 격리하며 원래 자료·초안을 삭제하거나 복원으로 대체하지 않는다. [.qa/.qa.backup 안내](../app/QA.md)를 따른다. 최신 후보의 API26 시작 오류 해결과 S23 수용은 잔여다.
- 실제 Notion 자료 이관은 사용자 승인됐지만 23:30 기준 실제 앱 반영은 없다. 기존 앱의 검증된 업데이트 전 복구 사본·계좌 대응·미확정값 확인을 먼저 수행한다. 합성 QA 백업은 실제 앱 사본을 대신하지 않으며, 원본·기존 앱 자료를 보존한다. 호스트 합성 시험과 이관 승인은 실제 자료의 적합성·완전성 또는 반영 완료를 뜻하지 않는다. 배포·릴리스 보류는 유지한다.
- 23:30 전체 완료 목표는 미달이다. 시간 도달로 미검증을 통과 처리하거나 미확정 금융값을 채우지 않는다.
- CodeRabbit은 사용자의 재활성화 요청 전까지 지속 OFF다. [운영 기준](ai/automation.md#coderabbit-선택적-리뷰)에 따라 요청·대기하지 않으며 최신 head 필수 CI와 직접 검토는 유지한다.

## 문서 찾기와 이력의 읽는 법

| 문서 묶음 | 읽는 목적과 상태 |
|---|---|
| [루트 README](../README.md) | 앱 소개·개발 환경·현재 범위의 진입점 |
| [common](../core/common/README.md)·[data](../core/data/README.md)·[designsystem](../core/designsystem/README.md)·[navigation](../core/navigation/README.md) README | 현재 모듈 사용 계약. designsystem의 Prevue·host 이미지는 실기기 결과와 구분 |
| [home README](../feature/home/README.md)·[STATE](../feature/home/STATE.md)·[REMINDERS](../feature/home/REMINDERS.md) | 홈·납입·알림 현재 계약 |
| [transactions README](../feature/transactions/README.md)·[ENTRY](../feature/transactions/ENTRY.md)·[STATE](../feature/transactions/STATE.md) | 거래 조회·신규 입력·수정/삭제 현재 계약 |
| [accounts README](../feature/accounts/README.md)·[STATE](../feature/accounts/STATE.md)·[ASSETS](../feature/accounts/ASSETS.md) | 계좌 조회·등록·기준액/자산 현재 계약 |
| [공통 지침](../AGENTS.md)·[DoD](ai/definition-of-done.md)·[역할 운영](ai/workflow.md)·[자동화](ai/automation.md) | 책임·승인·검증·인계 절차. [역할별 문서](../AGENTS.md#역할-선택과-필요한-문서-읽기), [디자인 연동](ai/design-system.md), [학습 기록](ai/learning-log.md) 포함 |
| [CI 범위·시간](ci/validation.md) | 현재 검사 선택과 과거 측정 기록. 이전 구성의 시간·성공을 현재 head 결과로 재사용하지 않음 |
| [모듈·DI·UI 계획](architecture/monit-modules-di-ui-plan.md)·[Navigation 2 계획](architecture/navigation2-navigation-plan.md)·[PR #15 개선 설계](architecture/pr-15-account-state-improvement-plan.md) | 작성 당시 기준·선택 이유를 보존하는 이력. 현재 구현은 위 모듈 계약 우선 |
| [초안 계획](architecture/unsubmitted-draft-plan.md)·[선행 이관 미리보기](architecture/import-preview-plan.md) | 해당 변경 당시 schema·시험 범위의 이력. 현재 전체 기능의 미구현 목록으로 읽지 않음 |
| [백업](qa/backup-restore-host.md)·[이관](qa/import-writer-host.md)·[S23 결함 수정](qa/s23-acceptance-fixes.md) host 기록 | 당시 소스·APK·시험 결과 보존. 최신 APK·기기 상태는 별도 인계와 대조 |
| [로컬 커밋](../.agents/skills/commit-create/SKILL.md)·[PR 작성](../.agents/skills/pr-create/SKILL.md)·[테스트](../.agents/skills/write-tests/SKILL.md)·[안정성 분석](../.agents/skills/stability-analysis/SKILL.md)·[학습 기록](../.agents/skills/learning-log/SKILL.md) | 기존 작업별 절차. 스킬 존재는 외부 변경·병합 권한을 부여하지 않음 |

root 작업 공간에만 있는 미추적 계약·운영 초안은 이번 점검에서 게시하지 않았다. `docs/architecture/local-ledger-contract.md`, `local-mvp-android-plan.md`, `docs/contracts/README.md`와 미추적 계약들, `docs/ai/android-skills.md`·`merge-readiness.md`·`model-policy.md`·`remote-development.md`, `docs/testing/`은 이 기준 main의 공개 원본으로 연결하지 않는다. 이관 입력 형식처럼 이미 추적된 문서와 구분한다. 필요한 원본 승격·외부 문서 대조는 PM의 별도 정리 대상으로 남긴다.
