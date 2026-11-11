# Android CI 실행 범위와 시간

필수 job 이름은 `build`다. 앱 빌드·단위 테스트·Lint·독립 컴파일을 4개 job에서 병렬 검증하고, 모든 결과가 성공일 때만 `build`가 성공한다. 실패·취소·생략 결과는 통과시키지 않는다. 모든 PR·main push·수동 실행에서 완료 상태를 남기며 workflow 전체를 `paths-ignore`로 생략하지 않는다. 제품 코드·검증 입력이 달라졌거나 판단할 수 없으면 Android 빌드·단위 테스트·Lint를 유지한다.

앱 빌드는 `:app:assembleDebug`로 앱 APK와 필요한 모듈을 컴파일한다. 내부 라이브러리 배포용 AAR 패키징은 요청하지 않는다. 단위 테스트는 `:core:model:test testDebugUnitTest`, Lint는 `lintDebug`로 전체 모듈을 검사한다. 문서·diff·분류기 검사는 독립 컴파일 job에서 한 번 실행해 앱 빌드의 시작을 지연시키지 않는다.

## 검사별 선택

| 변경 입력 | 문서·diff | Android 빌드/단위/Lint | 공통 UI 독립 컴파일 |
| --- | --- | --- | --- |
| 허용된 Markdown·문서 이미지·루트 라이선스만 | 실행 | 해당 없음 | 해당 없음 |
| 앱·core·feature의 제품/테스트 소스 | 실행 | 실행 | 해당 family/theme 입력을 확인 |
| DS button/chip/textfield 소스 | 실행 | 실행 | 바뀐 family만 |
| DS theme·리소스·Manifest·assets·debug/미인식 소스 집합 | 실행 | 실행 | 3개 모두 |
| DS의 다른 컴포넌트·Preview·호스트 테스트 | 실행 | 실행 | 선택 소스 집합에 영향 없어 생략 |
| 공통 빌드·의존성·환경·CI·검증 스크립트 | 실행 | 실행 | 3개 모두 |
| 알 수 없는 경로/파일 형식·diff 판별 실패·수동 실행 | 실행 | 실행 | 3개 모두 |

문서 판별은 크기나 확장자 하나에 의존하지 않는다. 소스 집합 안의 README·이미지는 런타임 asset일 수 있어 Android 입력으로 처리한다. JSON Schema·fixture·스크립트는 문서 폴더에 있어도 문서 전용으로 판정하지 않는다. Kotlin 주석만 바뀌었다는 의미 분석은 하지 않으며 소스 입력으로 처리한다.

PR은 이벤트의 base/head와 실제 checkout merge 부모를 확인하고 **base→검사할 merge tree** 전체를 비교한다. 마지막 커밋만 보고 앞선 미검증 제품 변경을 숨기지 않는다. 따라서 제품 변경이 남아 있는 PR에 문서 커밋을 추가하면 Android 명령은 계속 요청될 수 있다. 변경되지 않은 task는 Gradle이 입력에 따라 캐시를 재사용한다. 삭제·이름변경은 이전/새 경로 모두를 검사한다. main push는 before→after 전체를 비교하며 누락 이력·잘못된 SHA·예상하지 않은 병합이면 전체 검사로 돌아간다.

필터는 NUL 구분 Git 출력과 인자 배열을 사용한다. 이벤트 값·파일명을 셸 코드에 삽입하지 않는다. 필터 프로세스가 실패해도 출력 기본값은 전체 검사이며, 필요한 검사 단계의 실패는 필수 `build`를 실패 처리한다. PR·push의 비교 범위를 확인하지 못하면 공백 검사도 실패 처리한다. 수동 실행에서만 HEAD의 첫 부모 기준 공백 검사를 사용한다.

## 독립 컴파일과 캐시

`scripts/verify-component.init.gradle.kts`의 선택 소스·resource-only 프로젝트 classpath·family별 출력·별도 프로젝트 캐시·`-Pkotlin.incremental=false`·peer-class 누수 검사를 그대로 유지한다. 독립 컴파일의 task 캐시는 계속 비활성이다. 관련 입력이 같은 경우에만 해당 독립 검사를 요청하지 않는다. theme/공통 빌드가 바뀌면 기존대로 3회를 실행한다.

일반 빌드에는 `org.gradle.caching=true`를 사용한다. 기존 `gradle/actions/setup-gradle@v4`가 Gradle User Home의 의존성·변환 결과·로컬 build cache를 저장/복원한다. `FROM-CACHE`는 Gradle이 task 입력으로 재사용을 판정한 결과이며, 단순히 cache restore가 성공했다는 이유로 테스트 실행 성공을 주장하지 않는다.

main push의 단위 테스트 job 하나만 GitHub가 허용한 ref 범위에 캐시를 쓴다. 다른 job·모든 PR·일반 수동 실행은 캐시를 읽기만 한다. PR의 새 작업 결과는 병합 전까지 후속 PR 실행의 캐시에 축적되지 않는 절충이 있다. fork의 캐시를 main으로 가져오는 별도 키나 외부 저장소를 만들지 않는다. 인증정보·서명키·기기 데이터·원격 build cache는 추가하지 않는다. [GitHub 캐시 접근 경계](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching)와 [setup-gradle](https://github.com/gradle/actions/blob/v4/setup-gradle/action.yml)을 따른다.

일반 Android 단계는 `--parallel`을 사용하며 앱 빌드는 `--max-workers=4`, 단위 테스트·Lint는 `--max-workers=2`로 제한한다. 일반 검증의 Gradle heap은 4GB, Kotlin daemon은 2GB다. 로컬 `gradle.properties`와 독립 컴파일의 2GB 설정은 유지하고, 독립 컴파일 3개를 동시에 띄우지 않는다. KSP·고정 버전·정적 debug 값·멀티모듈은 기존 적용 사항이다. AGP 기본 non-transitive/non-constant R와 debug PNG crunch 비활성은 새 개선 성과로 계산하지 않는다.

configuration cache는 이번 변경에서 활성화하지 않는다. 구성 단계가 실제 병목으로 남을 때 플러그인·독립 컴파일 init-script 호환성을 확인하고 `Reusing configuration cache` 로그로 따로 검증한다. 새 flavor·runner·유료 서비스·도구 버전 전환·리소스 제거도 포함하지 않는다.

## 재현과 측정

필터 자체 검사는 `node --test .github/scripts/ci-impact.test.mjs`로 실행한다. 실제 Git rename/merge fixture와 삭제·런타임 문서형 asset·공통 입력·수동 실행·필터 실패·적대적 파일명 사례를 포함한다. CI/공통 입력이나 판별 실패에서는 CI도 이 검사를 실행한다.

수동 실행은 항상 전체 검사를 요청한다. `cold_cache=true`이면 빈 Gradle User Home을 만들고 `setup-gradle` 캐시 복원·저장을 모두 비활성화한다. 새 runner의 첫 실행이며 아티팩트도 업로드하지 않는다. 이때 Gradle JVM의 GC pause 요약을 실행 로그에 출력한다. 이 수치는 동시 GC와 Kotlin·테스트 JVM의 GC 시간을 포함하지 않는다. `gradle_heap`은 일반 검증의 2GB/4GB 비교용이며 기본값은 4GB다. 기본 수동 실행은 캐시 읽기 경로를 사용한다.

시간은 실제 GitHub run의 `created_at`부터 필수 `build` 완료까지, 그리고 job `started_at`부터 `completed_at`까지 각각 기록한다. task cache 없는 실행·캐시 있는 실행·문서 전용 실행을 분리하고 head와 검사 범위를 함께 남긴다. queue·runner 준비·캐시 다운로드/업로드·post-action도 포함한 시간을 사용한다. `Build, unit tests and lint`는 묶음 단계이며 테스트 단독 시간으로 표시하지 않는다.

### 이전 단일 job 구성의 GitHub 측정 (2026-11-11, UTC)

아래 수치는 병렬 job 전환 전의 기록이다. 현재 cold 전체 검증 목표는 생성부터 필수 `build` 완료까지 3분이며, 이 기록을 현재 구성의 성능으로 간주하지 않는다.

모두 GitHub-hosted `ubuntu-latest`의 필수 `build` 성공 결과다. 전체 검증은 제품 코드가 같은 main `3c5e434`를 기준으로 CI 설정·캐시 조건만 다르다. 시간은 초 단위이며 큐·캐시·후처리를 포함한다. 이전 #29 124초와 DS #26 248초는 제품 코드·캐시 조건이 달라 직접 비교하지 않는다.

| 조건·검사 범위 | head | run | 생성→build 완료 | job 시작→완료 |
| --- | --- | --- | --- | --- |
| 변경 전 main, Android+독립 3회, task cache 비활성 | `3c5e434` | [42983479577](https://github.com/1971123-seongmin/MONIT/actions/runs/42983479577) | 298초 | 295초 |
| 첫 개선 PR, Android+독립 3회, 기존 의존성 캐시 복원·PR 캐시 저장 | `20709f6` | [42986781973](https://github.com/1971123-seongmin/MONIT/actions/runs/42986781973) | 206초 | 203초 |
| 문서 전용 임시 PR, 문서·diff만 실행 | `151945f` | [42987278728](https://github.com/1971123-seongmin/MONIT/actions/runs/42987278728) | 11초 | 8초 |
| 리뷰 수정 PR, Android+독립 3회, task cache 복원·PR read-only | `34a1e49` | [42988356390](https://github.com/1971123-seongmin/MONIT/actions/runs/42988356390) | 86초 | 82초 |
| cold 수동 실행, Android+독립 3회, 기존 Gradle 캐시 복원 없음 | `34a1e49` | [42988605423](https://github.com/1971123-seongmin/MONIT/actions/runs/42988605423) | 343초 | 339초 |
| 캐시 있는 Kotlin 변경 임시 PR, Android 실행·독립 검사 입력 불변으로 생략 | `d829106` | [42989446621](https://github.com/1971123-seongmin/MONIT/actions/runs/42989446621) | 90초 | 86초 |

| 전체 검증 단계 | 변경 전 main | 첫 개선 PR | 리뷰 수정 PR | cold 수동 실행 |
| --- | --- | --- | --- | --- |
| Gradle 캐시 준비·복원 | 15초 | 14초 | 16초 | 2초 (복원 없음) |
| 독립 컴파일 3회 | 56초 | 36초 | 36초 | 111초 |
| 일반 빌드·단위 테스트·Lint | 176초 | 118초 | 16초 | 187초 |
| Gradle 후처리·캐시 저장 | 39초 | 27초 | 1초 | 32초 |

일반 빌드 묶음의 Gradle 요약은 첫 개선에서 `293 actionable tasks: 264 executed, 29 from cache`, 리뷰 수정에서 `293 actionable tasks: 159 executed, 134 from cache`였다. `:core:model:test`·`:core:data:testDebugUnitTest`·`:core:designsystem:testDebugUnitTest`·`:feature:accounts:testDebugUnitTest` 등은 task 입력이 일치하면 기존 결과를 재사용한다. `NO-SOURCE`나 캐시 복원을 새 테스트 실행으로 계산하지 않는다. 독립 컴파일 task 자체는 캐시하지 않으며 resource 생성 등 다른 task의 캐시 사용과 구분한다.

첫 개선의 생성→완료는 변경 전보다 약 31%, 캐시가 있는 전체 검증은 약 71% 짧았다. 후자는 task cache가 채워진 조건과 PR 업로드 생략이 함께 작용한 결과이며 한 설정의 단독 효과가 아니다. 기존 의존성 캐시를 복원한 첫 개선을 캐시 없는 cold 실행이라고 부르지 않는다.

cold 실행 로그는 `cache-write-only: true`이며 `Restored cache entry`가 없었다. 일반 빌드의 `29 from cache`는 이 job 앞부분의 독립 컴파일이 만든 resource 등 동일 입력의 재사용으로, 이전 실행 캐시 복원을 뜻하지 않는다. cold의 343초는 의존성 다운로드와 첫 구성·컴파일·테스트·캐시 저장을 포함한다. 변경 전 main은 기존 의존성 캐시를 사용하는 실행이므로 cold 343초와 변경 전 298초를 설정 효과의 직접 비교로 사용하지 않는다.

캐시 있는 코드 측정은 base `34a1e49`의 cold 실행이 저장한 캐시를 사용할 수 있는 임시 PR #32에서 수행했다. 제품 동작을 바꾸지 않는 `core:model` Kotlin 주석 2줄과 측정용 Markdown만 포함하며 병합하지 않는다. 실제 분류는 `android=true`, `components=[]`였고 독립 단계는 생략됐다. 캐시 복원 8초, 일반 빌드·단위 테스트·Lint 66초, Gradle 후처리 0초였다. `293 actionable tasks: 188 executed, 105 from cache`이며 `:core:model:compileKotlin`·`:core:model:test`·`:core:data:testDebugUnitTest`·`:feature:accounts:testDebugUnitTest`는 다시 실행됐다. 소스 위치 정보가 달라지는 작은 주석 변경도 입력 변경으로 취급한 대표 사례이며, 실제 금융 로직 변경 전부의 성능을 대표하지 않는다. cold와 이 실행은 독립 검사 범위도 달라 시간 차이 전체를 캐시만의 효과로 계산하지 않는다.

66초와 앞선 16초도 같은 시작 조건이 아니다. 코드 변경 경로는 `08:45:01`에 새 Gradle daemon을 시작했고, 명령 시작 약 8초 뒤 buildSrc, 약 14초 뒤 첫 제품 모듈 task가 출력됐다. 반면 전체 검증의 일반 빌드는 앞선 독립 컴파일 3회가 가동한 daemon을 이어 사용했다. 코드 변경 뒤 KSP·관련 테스트·Lint도 일부 다시 실행됐다. task 로그는 완료 시점 중심이라 daemon·구성·개별 작업의 시간을 독립적으로 정확히 분해할 수 없으며, 50초 차이 전체를 소스 변경이나 daemon 하나의 비용으로 단정하지 않는다. 별도 예열 명령은 전체 시간을 옮길 뿐이라는 점도 고려한다. configuration cache는 후보지만 현재 init-script·플러그인 호환성 및 효과를 검증하지 않아 이번에 활성화하지 않았다. 근거 없이 worker 수·heap을 늘리거나 필수 검사를 줄이는 추가 변경도 하지 않았다.

문서 전용 측정도 임시 PR #32를 사용했고 JDK·Gradle·독립 컴파일·Android 단계가 모두 생략됐다. 측정 시점은 `20709f6` 분류기이며 이후 수정은 범위 확인 실패·DS 변형 입력·PR 캐시 정책이다. 정상적인 문서 전용 분류·diff 경로에는 영향이 없어 해당 11초 근거를 재사용했다. 최종 로컬 회귀 검사는 24개 통과했다.

목표는 1분 이내다. 문서 전용 경로는 충족했지만, 캐시 있는 코드 변경 90초·전체 검증 86초·cold 343초는 미달했다. 코드 변경 경로는 일반 빌드 묶음 66초, 전체 경로는 독립 컴파일 36초와 캐시 복원 16초가 주요 비용이었다. 작업 범위·Gradle daemon·다운로드·task 입력·runner 상태가 달라지므로 모든 코드 변경에 1분을 보장하지 않는다. 추가 개선은 구성·컴파일·Lint 등의 병목을 따로 측정한 뒤 검토하며, 이번 결과를 맞추기 위해 검증이나 독립 컴파일 격리를 제거하지 않는다.

참고 원칙은 [Android 빌드 최적화](https://developer.android.com/build/optimize-your-build?hl=en)와 [Duolingo 사례](https://www.linkedin.com/pulse/how-we-sped-up-android-ios-builds-50-duolingo-g4pie)다. first-clean과 daemon warm-up 차이를 새 설정의 효과로 단정하지 않는다.
