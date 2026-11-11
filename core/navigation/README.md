# 탐색 실행기

Navigation 2의 이동·Back·작은 UI 결과 반환을 실행한다. 앱·feature·금융 모델에 의존하지 않으며 제품 이동 정책은 app의 MainNavigator가 소유한다.

UI에서 `rememberNavigator(controller)`로 생성한다. 같은 controller에서는 인스턴스를 유지하고 composition 이탈·controller 교체 시 이전 연결을 닫는다. Hilt singleton·ViewModel·공유 CoroutineScope에 보관하지 않는다. 호출 시 현재 클릭을 발생시킨 `NavBackStackEntry`를 source로 전달하며 생성 시 source를 고정하지 않는다.

| API | 계약 |
| --- | --- |
| `navigate(source, route, options)` | 타입 경로와 Navigation 옵션으로 이동 |
| `back(source, matchesCaller)` | 실제 바로 아래 caller 한 단계로 복귀. 선택 조건이 다르면 변경 없음 |
| `returnWithResult(source, matchesCaller, resultKey, result)` | 바로 아래 caller를 검증하고 Bundle을 기록한 뒤 한 단계 pop. pop 거부·예외 시 이전 키 부재/값/null을 복구 |

모든 호출은 main thread에서 수행한다. 현재 entry와 동일한 RESUMED source만 실행한다. 닫힌 연결·비활성·이전 source는 `IGNORED`, caller 부재·불일치·pop 거부는 `UNAVAILABLE`, 실행 완료는 `EXECUTED`다. 개발 오류와 예외는 성공으로 바꾸지 않고 전파한다. 실행을 대기열에 보관하거나 새 host에 재생하지 않는다.

caller는 Navigation의 `previousBackStackEntry`로 얻는 직전 실제 목적지이며 graph entry는 제외한다. `matchesCaller`에는 수신 feature의 타입 경로와 화면 인스턴스 ID를 대조하는 조건을 전달한다. 더 아래에서 일치하는 entry를 검색하지 않는다.

UI 결과 저장과 이동은 영속 트랜잭션이 아니다. 금융 저장·영수증·제출 복구·프로세스 종료 후 정확히 한 번 전달을 보장하지 않는다. 수신 feature가 문맥을 검증하고 멱등 반영을 확인한 뒤 자신의 UI 결과 키만 제거한다.

Navigation은 기존 SDK 36을 유지하는 **2.9.8**, 타입 경로는 Kotlin 2.2.10 serialization plugin과 runtime **1.9.0**을 사용한다. [Navigation 릴리스](https://developer.android.com/jetpack/androidx/releases/navigation)와 [serialization 1.9.0 요구사항](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.9.0)을 기준으로 선택했다. 최신 Navigation 계열로의 SDK 상향은 이번 기반 구현에서 분리한다.

실제 NavController를 사용하는 Robolectric 시험은 source 수명·연타·caller 불일치·pop 거부/예외 복원·stack 저장/복원을 검증한다. Compose 시험은 재구성 시 동일 인스턴스와 host 제거/교체 시 이전 연결 종료를 확인한다. 실제 release 기기의 프레임 성능과 제품 화면 복구 검증은 후속 단계다. [전체 설계](../../docs/architecture/navigation2-navigation-plan.md)를 따른다.
