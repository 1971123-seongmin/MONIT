---
name: stability-analysis
description: Evidence-based, read-only stability analysis of one specified MONIT class, function, or call path, focusing on relevant coroutine, synchronization, exception, state, and lifecycle behavior. Use for a named analysis target, not general architecture reviews or implementation.
---

# Stability Analysis

지정한 대상의 실제 실행 흐름을 추적해 안정성 문제를 검증한다. 제품 코드·테스트·빌드 설정을 수정하지 않는다. 별도 문서는 요청받은 경우에만 작성한다.

## 범위와 근거

- 호출 메시지에서 대상 클래스·함수·호출 경로, 증상, 선택한 관점을 읽는다. 대상이 없거나 저장소 전체처럼 넓으면 실제 코드에 근거한 분석 후보를 제시한다.
- AGENTS.md와 현재 구현·요구사항·API 계약을 확인한다. 정상 사용에서 가능한 동시 호출·실패·취소를 기준으로 판단하고, 기획 중인 기능을 이미 구현되었다고 가정하지 않는다.
- 결과가 최종 호출자에게 전달되는 경로까지 추적한다. 범위 밖 관찰은 짧게 남긴다. Flavor·Build Type은 실제 동작 차이가 있을 때만 구분한다.
- Room·Supabase·Android 프레임워크·라이브러리의 동작을 구현 버전과 대조한다. 소스나 실행 근거를 확인할 수 없는 외부 동작은 가정으로 명시한다.

## 관점 선택과 실행 흐름

존재하는 요소에 맞는 관점만 적용한다. 사용자가 관점을 제한하면 그 범위를 따른다. 적용 관점과 필요한 제외 이유를 간단히 설명한다.

| 관점 | 적용 조건 |
|---|---|
| coroutine | scope, Job, dispatcher, suspend, Flow, 취소 |
| sync | 공유 상태의 동시 접근 또는 동기화 수단 |
| exception | 예외의 발생·변환·처리·전파 |
| state | 초기화·완료·실패·재시도·저장·이벤트 전달 |
| lifecycle | 객체·작업·자원의 생성·공유·해제 |

실제 경로에 파일과 줄 번호를 연결한다. 관련 dispatcher·스레드, 중단점, 잠금 획득·해제, 공유 상태 노출, DB 트랜잭션·네트워크·콜백 경계를 추적한다.

### Coroutine

- scope 소유자, 부모 Job, 종료 주체와 자식 관계를 확인한다. Job·SupervisorJob·await에 따른 실패·취소 전파와 종료된 scope의 재사용을 검증한다.
- API 반환·콜백 시점과 작업 완료를 구분한다. 별도 scope·Job, NonCancellable, timeout, 병렬 자식 대기의 범위를 확인한다.
- 긴 연산의 협력적 취소와 main safety를 검증한다. blocking 호출이 취소 요청만으로 즉시 중단된다고 가정하지 않는다. ThreadLocal·스레드 소유 자원 등이 있으면 실제 재개 스레드 제약을 확인한다.

### Sync

- 공유 상태의 모든 읽기·쓰기를 보호 수단과 대조한다. check-then-act 등 복합 연산은 단일 atomic 연산·Volatile·읽기 전용 타입만으로 안전하다고 판단하지 않는다.
- 초기화 전 객체 노출과 메모리 가시성을 확인하고, 해당 읽기·쓰기 경로에 실제 보장이 적용되는지 검증한다.
- 잠금 획득 순서, 재진입 불가능한 Mutex 재획득, 잠금 중 콜백·다른 잠금·작업 대기를 추적한다. 대기 순환과 진행 가능성을 확인한다.

### Exception

- 발생 지점부터 최종 호출자까지 처리·변환·재전파·무시를 추적하고 반환값·오류 상태·크래시·cause 보존을 확인한다.
- await가 없다는 이유만으로 예외 유실을 단정하거나 join을 실패 전달로 오인하지 않는다. 부모 Job·supervision과 함께 판단한다.
- catch·runCatching 등이 CancellationException을 삼키거나 실패를 성공으로 바꾸는지 검증한다.

### State

- 상태 필드·변경 권한·전이·불변식을 정리한다. 읽기 전용 인터페이스와 내부 객체의 불변성을 구분한다.
- 정상·중간 실패·취소·동시 호출·재시도에서 실제 구현된 메모리·Room·Supabase 상태의 일관성을 검증한다. 트랜잭션 경계·중복 처리·충돌·사용자 데이터 격리는 대상 경로에 있을 때만 포함한다.
- 금액이 포함되면 통화·정밀도·오버플로·중복 집계를 확인한다. 수동 완료 기록을 실제 은행 이체 성공으로 해석하지 않는다.
- StateFlow의 equals 기반 방출 억제·중간 값 생략과 이벤트 계약을 대조한다. SharedFlow는 구독자 부재·느린 수집자·재구독에서 replay·버퍼·overflow를 검증한다. 방출 성공은 수신·처리 완료를 보장하지 않는다.

### Lifecycle

- 객체·자원의 소유자·수명·해제 경로와 장기 참조·리스너·콜백·진행 작업을 확인한다.
- 생성·재초기화·해제가 겹칠 때 중복 생성, 이전 작업의 지연 결과, 사용 중 자원의 선행 해제를 확인한다. 취소 요청과 종료 완료를 구분한다.
- Flow 공유 scope·SharingStarted·수집 Job의 시작·중단·재시작과 부작용 중복을 확인한다.
- Compose UI가 경로에 있을 때 lifecycle-aware 수집과 effect의 소유권·취소·재시작을 검증한다. UI 수명 규칙을 Repository·백그라운드 작업에 일괄 적용하지 않는다. lifecycleScope는 STOPPED만으로 취소되지 않는다.

## 발견 사항의 기준

각 결함에 발생 조건·경로, 기존 보호 수단으로 방지되지 않는 이유, 호출자가 관찰하는 영향과 코드 위치를 연결한다. 동시 실행 순서가 원인인 경우에만 구체적인 interleaving을 제시한다.

- 확인: 코드 또는 실행 결과로 발생 조건과 원인이 검증됨. 정적 확인과 실행 재현 여부를 구분한다.
- 조건부: 특정 순서·스레드·외부 동작에서 성립함. 필요한 조건과 실제 확인 여부를 밝힌다.
- 미검증 후보: 핵심 조건이 확인되지 않음. 결함으로 확정하지 않고 필요한 검증을 기록한다.

위험 패턴·권장사항 위반·타임아웃만으로 결함을 확정하지 않는다. 우선순위는 실제 데이터 유실·중복·금액 오류·사용자 격리 위반·크래시·누수 등의 영향과 발생 가능성에 근거한다. 단순 DI 도입이나 API 형태 통일 제안은 결함에 섞지 않는다.

## 검증과 보고

기존 테스트를 근거로 사용하면 실제 assertion과 실행 결과를 확인한다. 재현을 제안할 때는 전제 조건·실행 순서·예상 관찰 결과를 적는다. 단일 스레드 테스트 통과나 advanceUntilIdle 후 최종 상태만으로 멀티스레드 안전성을 확정하지 않는다. 구독 준비와 무한 Flow 수집의 정리도 고려한다.

다음 순서로 필요한 내용만 한국어로 보고한다.

1. 대상·호출 경로·적용 관점과 주요 실행 흐름. 동기화 분석에는 공유 상태와 보호 수단을 포함한다.
2. 발견 사항별 근거 수준·조건·원인·영향·위치·우선순위와 수정 원리.
3. 반증되어 제외한 의심 사항, 미검증 후보와 검증 방법, 실행한 검증과 미실행 항목.

테스트 작성이 요청되면 별도 write-tests 작업에 전달할 조건·실행 순서를 정리한다. 이 분석 스킬이 테스트나 제품 수정을 자동으로 시작하지 않는다.

## 공식 참고 자료

대상에 관련된 의미만 현재 프로젝트 버전과 대조한다.

- [Coroutines best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)
- [StateFlow와 SharedFlow](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow)
- [Coroutine tests](https://developer.android.com/kotlin/coroutines/test)
- [Kotlin Job](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-job/)
