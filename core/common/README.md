# 공통 Coroutine DI

공통 Coroutine 실행 환경을 Hilt로 제공한다. 소비자는 생성자로 `CoroutineContextProvider` 또는 `CoroutineScopeProvider`를 주입받는다.

| Provider | 제공 값 |
| --- | --- |
| `CoroutineContextProvider` | `main = Dispatchers.Main`, `io = Dispatchers.IO`, `default = Dispatchers.Default` |
| `CoroutineScopeProvider` | `ioScope = CoroutineScope(SupervisorJob() + contexts.io)`, `defaultScope = CoroutineScope(SupervisorJob() + contexts.default)` |

Dispatcher는 `@MainDispatcher`·`@IoDispatcher`·`@DefaultDispatcher`로 구분하며 모두 `@Qualifier`, `@Retention(AnnotationRetention.BINARY)`를 사용한다. Context Provider의 프로퍼티 타입은 `CoroutineContext`의 요소인 `CoroutineDispatcher`다. 여기서 Context는 코루틴 실행 환경을 뜻한다.

두 Provider는 `SingletonComponent`의 `@Binds`·`@Singleton`으로 제공한다. Scope Provider는 최초 주입 시 생성되며 IO·Default가 각각 독립 `SupervisorJob`을 소유한다. 프로세스 동안 공유하므로 소비자는 개별 작업의 Job을 취소하고, 작업 예외는 호출 경계에서 처리한다. 프로세스 종료 뒤 실행 지속을 보장하지 않는다.

ViewModel은 기본 `viewModelScope.launch`를 사용하고 제거 시 취소된다. 공유 Scope는 화면보다 오래 살아야 하는 작업을 명시적으로 구현할 때 사용한다. 현재 계좌 저장에는 공유 Scope를 사용하지 않는다.

Room의 suspend DAO·트랜잭션은 Room의 실행 컨텍스트를 유지한다. Repository는 주입받은 `CoroutineContextProvider.default`로 요청 직렬화·해시·복원 준비를 실행한다. `withContext`는 호출자의 Job과 취소 관계를 유지한다.

취소 시 이미 기록된 제출 의도나 금융 저장의 결과는 취소 예외만으로 판단하지 않는다. [Repository 복구 계약](../data/README.md)과 [계좌 결과 확인·재시도](../../feature/accounts/STATE.md)를 따른다. Provider 테스트는 TestDispatcher 주입으로 실행 위치·Supervisor 실패 격리·Scope 간 취소 격리를 검증한다. Hilt 그래프 컴파일과 기기에서의 실제 주입 검증은 구분한다.

근거: [Android Coroutine 권장 사항](https://developer.android.com/kotlin/coroutines/coroutines-best-practices).
