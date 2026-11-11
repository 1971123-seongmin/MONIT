# Android CI 실행 범위와 시간

필수 job 이름은 `build`다. 모든 PR·main push·수동 실행에서 완료 상태를 남기며 workflow 전체를 `paths-ignore`로 생략하지 않는다. 제품 코드·검증 입력이 달라졌거나 판단할 수 없으면 기존 Android 빌드·단위 테스트·Lint를 유지한다.

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

모든 PR은 base/main의 캐시를 읽기만 하며 PR 전용 캐시 업로드 시간을 줄인다. main·수동 실행은 GitHub가 허용한 해당 ref 범위에 캐시를 쓴다. PR의 새 작업 결과는 병합 전까지 후속 PR 실행의 캐시에 축적되지 않는 절충이 있다. fork의 캐시를 main으로 가져오는 별도 키나 외부 저장소를 만들지 않는다. 인증정보·서명키·기기 데이터·원격 build cache는 추가하지 않는다. [GitHub 캐시 접근 경계](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching)와 [setup-gradle](https://github.com/gradle/actions/blob/v4/setup-gradle/action.yml)을 따른다.

일반 Android 단계만 `--parallel --max-workers=2`로 제한한다. 독립 컴파일 3개를 동시에 띄우지 않는다. 기존 Gradle heap 2GB를 유지하며, 측정 없이 6~8GB heap·GC 변경을 추가하지 않는다. KSP·고정 버전·정적 debug 값·멀티모듈은 기존 적용 사항이다. AGP 기본 non-transitive/non-constant R와 debug PNG crunch 비활성은 새 개선 성과로 계산하지 않는다.

configuration cache는 이번 변경에서 활성화하지 않는다. 구성 단계가 실제 병목으로 남을 때 플러그인·독립 컴파일 init-script 호환성을 확인하고 `Reusing configuration cache` 로그로 따로 검증한다. 새 flavor·runner·유료 서비스·도구 버전 전환·리소스 제거도 포함하지 않는다.

## 재현과 측정

필터 자체 검사는 `node --test .github/scripts/ci-impact.test.mjs`로 실행한다. 실제 Git rename/merge fixture와 삭제·런타임 문서형 asset·공통 입력·수동 실행·필터 실패·적대적 파일명 사례를 포함한다. CI/공통 입력이나 판별 실패에서는 CI도 이 검사를 실행한다.

수동 실행은 항상 전체 검사를 요청한다. `cold_cache=true`이면 `setup-gradle`을 write-only로 실행해 기존 Gradle 캐시를 복원하지 않고 새 runner의 첫 실행을 측정한다. 생성한 캐시는 해당 ref에 저장할 수 있으므로 다음 대표 실행과 구분한다. 기본 수동 실행은 정상 캐시 경로를 사용한다.

시간은 실제 GitHub run의 `created_at`부터 필수 `build` 완료까지, 그리고 job `started_at`부터 `completed_at`까지 각각 기록한다. task cache 없는 실행·캐시 있는 실행·문서 전용 실행을 분리하고 head와 검사 범위를 함께 남긴다. queue·runner 준비·캐시 다운로드/업로드·post-action도 포함한 시간을 사용한다. `Build, unit tests and lint`는 묶음 단계이며 테스트 단독 시간으로 표시하지 않는다.

목표는 1분 이내지만 모든 코드 변경·cold 실행에 보장하지 않는다. 아래 실제 결과는 대표 실행 후 채운다. 이전 #29 124초와 DS #26 248초는 제품 코드·캐시 조건이 다른 참고치다. 동일 제품 코드의 main 기준 실행이 있으면 우선 비교한다.

참고 원칙은 [Android 빌드 최적화](https://developer.android.com/build/optimize-your-build?hl=en)와 [Duolingo 사례](https://www.linkedin.com/pulse/how-we-sped-up-android-ios-builds-50-duolingo-g4pie)다. first-clean과 daemon warm-up 차이를 새 설정의 효과로 단정하지 않는다.
