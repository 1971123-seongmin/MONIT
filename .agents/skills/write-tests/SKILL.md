---
name: write-tests
description: Design and write deterministic MONIT tests for a requested behavior, bug, or finding, including Kotlin unit, Android integration, Compose UI, and explicitly authorized Supabase tests. Also use for documenting existing Kotlin tests without changing their logic; does not implement unrelated product features.
---

# Write Tests

호출 메시지의 대상·동작·증상을 읽고 AGENTS.md와 현재 구현·요구사항을 기준으로 테스트 범위를 정한다. 사용자 설명과 테스트 문서는 한국어로 작성한다.

대상에 맞는 최소 시험 계층을 선택한다. 요구사항이나 원인을 추측해 새 기능을 확장하지 않는다. 아래의 절차와 [Kotlin 테스트 문서화](references/kotlin-test-documentation.md)를 적용하며 같은 참고 문서를 반복해서 로드하지 않는다.

## 계층 선택

| 대상 | 우선 검토할 계층 |
|---|---|
| 금액·날짜·상태 전이·ViewModel·대역으로 격리 가능한 Repository | Kotlin/JVM 단위 테스트 |
| Room DAO·트랜잭션·마이그레이션·WorkManager·플랫폼 의존성 | 실제 의존성이 필요한 Android 통합 테스트 또는 프로젝트에 구성된 JVM 환경 |
| 화면 표시·입력·접근성·상태 복원·탐색 | Compose UI 테스트 |
| Supabase 동기화·인증·RLS | 로컬 또는 명시적으로 승인된 테스트 환경의 통합 테스트 |
| 모듈 의존성·빌드 산출물·설정 | 필요한 빌드 작업·Lint·정적 검사 |

행동을 관찰할 수 있는 가장 작고 적합한 계층을 사용한다. ARM·JNI·C++ 계층은 실제 의존성이 있을 때만 고려한다. 운영 금융 데이터나 유료 자원을 테스트에 사용하지 않고, 미구성 계층을 테스트 요청만으로 대규모 도입하지 않는다.

## 기존 환경과 시나리오

- 같은 모듈의 관련 테스트·대역 1~2개, 실제 build.gradle.kts와 Version Catalog, GitHub Actions를 읽는다. 없는 파일이나 특정 테스트 라이브러리를 전제하지 않는다.
- 기존 Fake·Stub·Fixture와 도구를 우선 사용한다. Mockito·JUnit 버전을 무조건 고정하지 않는다. 새 의존성·제품 변경이 필요하면 이유와 현재 승인 범위를 확인한다.
- 버그·동작을 `전제 조건 → 제어할 실행 순서 → 호출자가 관찰할 기대 결과`로 적는다. 정상·실패·취소·경계·재호출·중복·계정 격리 중 관련 시나리오를 선택한다.
- 금액·날짜는 실제 계약의 통화·정밀도·시간대·Clock을 명시한다. 실제 금융 정보나 토큰 대신 합성 데이터를 사용한다.
- 필요한 주입 지점이 없으면 한계를 보고한다. 테스트 통과를 위해 승인되지 않은 제품 동작을 바꾸지 않는다.

## Coroutine과 Flow

- suspend 동작은 runTest를 우선 사용하고 TestDispatcher들이 동일 TestCoroutineScheduler를 공유하게 한다. 필요하면 StandardTestDispatcher(testScheduler)를 주입한다.
- 가상 시간은 해당 스케줄러에 연결된 작업에만 적용된다. 실제 dispatcher·blocking 호출을 구분하며 Thread.sleep이나 실제 시간 지연으로 순서를 맞추지 않는다.
- 코루틴 간 gate는 CompletableDeferred·Channel 등 중단 가능한 수단을 우선 사용한다. 테스트 스케줄러의 단일 스레드를 CountDownLatch.await로 막지 않는다.
- Flow는 방출 전 구독 준비, Lazily·WhileSubscribed 시작 조건, StateFlow 값 생략을 고려한다. 무한 수집은 backgroundScope 또는 명시적 취소로 정리한다.
- 전역 Dispatchers.Main을 바꿨다면 resetMain으로 복원하고, 직접 만든 실행 자원은 정리한다.

## 실제 스레드 경합과 진행 불가

- 실제 병렬 실행이 필요한 경우에만 전용 스레드·executor와 타임아웃 있는 CountDownLatch·CyclicBarrier 등을 사용한다. 단일 스레드 스케줄러 테스트와 구분한다.
- gate 진입과 해제 시점을 제어해 문제 순서를 재현하고, 횟수·동일성·최종 상태·결과 등 실제 계약을 단언한다. 주장이 필요한 관찰값에 맞는 assertion을 선택한다.
- 모든 blocking 대기에는 상한과 단계별 실패 메시지를 두고, 성공 여부도 단언한다. gate는 finally에서 해제하고 생성한 스레드·executor를 정리한다.
- 타임아웃만으로 deadlock을 확정하지 않는다. 대기 관계·진행 단계·자원 의존성으로 제품 교착과 테스트 자체의 스케줄링 문제를 구분한다.
- 결함 진단용 재현과 회귀 테스트를 구분한다. 회귀 테스트는 수정 후 정상 진행·완료를 기대하며 결함이 재발하면 실패해야 한다. 교착 timeout 발생 자체를 성공 조건으로 남기지 않는다.
- interrupt·취소로도 종료할 수 없는 작업은 같은 테스트 프로세스에 남기지 않는다. 안전한 격리가 없으면 실행하지 않고 한계와 별도 검증 방법을 보고한다. 우연한 반복 횟수로 결정성을 대신하지 않는다.

## 예외·취소·상태

호출자가 관찰하는 반환값·오류 상태·예외와 cause를 단언한다. 취소는 전파와 취소 후 정리·상태 불변식을 함께 확인한다. isCancelled만으로 정리 완료를 입증하지 않는다.

Room·동기화 시험은 실제 구현된 트랜잭션·중복·재시도·충돌·사용자 소유권을 기준으로 검증한다. 아직 구현되지 않은 요구사항을 테스트가 통과한 기능처럼 보고하지 않는다.

## 작성과 검증

1. 시나리오·계층·검증할 관찰값을 정하고 관련 테스트만 추가·수정한다. 하나의 시나리오가 여러 불변식을 요구하면 필요한 assertion을 함께 둔다.
2. Kotlin 테스트 문서는 참고 문서에 따라 작성하고, 추적 로그는 필요한 경우에만 검토한다.
3. 실제로 존재하는 작업 이름으로 대상 테스트와 영향받는 모듈의 필요한 검증을 실행한다. Flavor·Build Type은 구현 차이·영향이 있는 경우만 확장한다. 존재하지 않는 regression-check 스킬을 가정하지 않는다.
4. 버그 회귀 테스트는 가능하면 수정 전 실패와 수정 후 성공을 확인한다. 실패가 환경 오류가 아니라 같은 결함을 검출하는지 확인한다.
5. 수정이 이미 작업 트리에 있으면 사용자 변경을 되돌리거나 stash하지 않는다. 필요하고 허용된 경우 격리된 worktree·수정 전 revision에서 검증한다. 불가능하면 `수정 전 실패 미확인`으로 보고한다. 새 기능 테스트에는 수정 전 실패를 일괄 강제하지 않는다.
6. 추가·변경한 테스트, 실행 명령·결과, 실제 테스트 수 또는 NO-SOURCE, 수정 전 실패 확인 여부와 검증 한계를 한국어로 보고한다. Ignore·무의미한 assertion으로 실패를 숨기지 않는다.

## 문서화 전용 모드

기존 테스트 문서만 요청받으면 대상 테스트·대역·제품 계약을 읽고 KDoc만 변경한다. 테스트 로직·assertion·제품 코드·의존성·로그는 바꾸지 않는다.

diff가 주석에 한정되는지와 설명·assertion의 일치를 확인한다. 저장소 규칙·변경 규모·문법 위험에 맞춰 필요한 소스 컴파일 또는 테스트를 선택하며, 주석 변경만으로 모든 빌드 변형 실행을 강제하지 않는다. 발견한 불일치·검증 누락과 미수행 검증을 보고한다.

## 공식 참고 자료

- [Coroutine tests](https://developer.android.com/kotlin/coroutines/test)
- [Flow tests](https://developer.android.com/kotlin/flow/test)
- [Compose testing](https://developer.android.com/develop/ui/compose/testing)
