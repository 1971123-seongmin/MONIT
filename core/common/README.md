# 공통 Coroutine DI

`core:data`와 `feature:accounts`가 공유하는 Coroutine Dispatcher와 ViewModel Scope의 Hilt 제공부다. 업무 모델·Android Context 보관·금융 정책은 포함하지 않는다.

| Qualifier | 제공 범위와 사용처 |
| --- | --- |
| `@MainDispatcher` | `Dispatchers.Main.immediate`. ViewModel Scope의 실행 위치 |
| `@DefaultDispatcher` | `Dispatchers.Default`. Repository의 요청 직렬화·해시·복원 준비 |
| `@ViewModelCoroutineScope` | `ViewModelComponent`의 `@ViewModelScoped` Scope. `SupervisorJob`과 주입된 Main Dispatcher로 생성 |

세 Qualifier 모두 `@Qualifier`, `@Retention(AnnotationRetention.BINARY)`를 사용한다. 소비자는 생성자에 Qualifier를 명시하고 직접 `Dispatchers`를 선택하지 않는다. 테스트에서는 직접 생성자로 TestDispatcher/Scope를 전달한다.

ViewModel은 주입 Scope를 `ViewModel(scope)`에 전달해야 한다. 소유 ViewModel이 제거되면 AndroidX가 Scope를 취소한다. 다른 ViewModel 또는 앱 전체의 작업과 Scope를 공유하지 않는다. 앱 수명주기 백그라운드 작업은 현재 없어 별도 Application Scope를 제공하지 않는다.

Room의 suspend DAO·트랜잭션은 Room의 실행 컨텍스트를 유지한다. 요청 준비의 `withContext(defaultDispatcher)`는 호출자의 Job을 유지하므로 화면 취소가 전파되며, 새 Scope로 금융 저장을 분리하지 않는다.

취소 시 이미 기록된 제출 의도나 금융 저장의 결과는 취소 예외만으로 판단하지 않는다. [Repository 복구 계약](../data/README.md)과 [계좌 결과 확인·재시도](../../feature/accounts/STATE.md)를 따른다. Scope 수명주기 테스트는 직접 주입으로 검증하며, Hilt 그래프 컴파일과 기기에서의 실제 주입 검증은 구분한다.

Android Context는 Hilt의 기존 `@ApplicationContext`를 [DB 제공부](../data/src/main/kotlin/com/monit/core/data/di/LedgerDatabaseModule.kt)에서 받는다. Activity Context나 Context 전역 저장소를 추가하지 않는다.

근거: [Android Coroutine 권장 사항](https://developer.android.com/kotlin/coroutines/coroutines-best-practices), [ViewModel의 Scope 생성자](https://developer.android.com/reference/kotlin/androidx/lifecycle/ViewModel).
