# 캐시 없는 첫 CI 비교와 build-logic 검토

이슈 [#33](https://github.com/1971123-seongmin/MONIT/issues/33)의 2026-11-11 조사·실행 기록이다. 기존 캐시 개선은 [CI 실행 범위와 시간](validation.md)을 따른다. 이번 목표는 첫 실행 120초, 허용 상한 150초이며 세 대표 실행 모두 목표에 미달했다. 생산 workflow는 변경하지 않았다.

## 비교 조건과 비용

- 제품 코드는 main `43ba391a3801f502321052c62032bf7e2d4488fc`로 고정했다. 원본 작업 공간의 미커밋 변경은 포함하지 않았다.
- 공개 MONIT 저장소의 기본 `ubuntu-latest`만 사용했다. 비용 설명 후 사용자가 2→4 worker 비교를 승인했다. 이 기본 환경은 [GitHub 정책](https://docs.github.com/en/actions/reference/runners/github-hosted-runners#standard-github-hosted-runners-for-public-repositories)상 4 CPU·16GB이며 공개 저장소 실행은 무료다.
- 저장공간은 실행 비용과 별개이므로 비교에서 GitHub 캐시 복원·저장과 artifact 업로드를 모두 하지 않았다. 각 job은 빈 전용 Gradle User Home에서 시작했다. 프로파일·CPU·메모리 요약은 로그와 job summary에만 남겼다. [과금·저장 정책](https://docs.github.com/en/billing/concepts/product-billing/github-actions)을 확인했고 결제·저장 한도를 변경하지 않았다.
- JDK 21, Gradle·AGP·라이브러리 버전, Gradle heap 2GB, 제품 소스와 검사 종류를 고정했다. `--profile`과 동일한 관측기를 사용했다. 외부 Build Scan을 전송하지 않았다.
- 독립 button/chip/textfield는 모두 기존 init-script·`-Pkotlin.incremental=false`·family별 출력/프로젝트 캐시·resource-only classpath·peer-class 누수 검사를 유지했다. 독립 검사 자체의 worker는 두 비교 모두 2개다.
- 일반 Gradle 명령의 `--max-workers`만 2와 4로 바꿨다. 필수 문서·diff·분류기 회귀 검사 및 `:core:model:test assembleDebug testDebugUnitTest lintDebug`를 유지했다. 첫 두 실행은 순차 수행했다.
- 이후 분리 시험은 같은 제품에서 독립 검사/assembleDebug/단위 테스트/Lint를 각각 별도 무료 VM에서 실행했다. 작업의 합집합은 같고 중복 컴파일·다운로드는 늘어난다. 모든 matrix job이 성공해야 `build`가 성공하도록 했다.

## 실제 결과

모두 성공했다. 생성→완료는 run 생성부터 필수 `build` 완료까지이며 큐·준비·후처리를 포함한다.

| 방식 | 제품 | 측정용 코드 | 실행 | 생성→build 완료 | job 시간 |
| --- | --- | --- | --- | --- | --- |
| 전체 순차·일반 worker 2 | `43ba391` | `030a0cf` | [42994751557](https://github.com/1971123-seongmin/MONIT/actions/runs/42994751557) | 274초 | 269초 |
| 전체 순차·일반 worker 4 | `43ba391` | `030a0cf` | [42995535841](https://github.com/1971123-seongmin/MONIT/actions/runs/42995535841) | 328초 | 323초 |
| 검증 4종 분리·각 worker 2 | `43ba391` | `82bb2a8` | [42996739343](https://github.com/1971123-seongmin/MONIT/actions/runs/42996739343) | 237초 | 아래 개별 기록 |

| 전체 순차의 관측값 | worker 2 | worker 4 |
| --- | --- | --- |
| 독립 컴파일 3회 명령 시간 합 | 97.1초 | 113.2초 |
| 일반 빌드·단위 테스트·Lint 명령 | 157.2초 | 190.6초 |
| Gradle 실행 동안 VM CPU 평균 busy | 79% | 88% |
| VM 전체 메모리 사용량 관측 최대 | 6053MiB | 6683MiB |

메모리는 `MemTotal - MemAvailable`를 1초 간격으로 본 VM 전체 값이며 개별 JVM heap이나 정확한 순간 최대가 아니다. CPU도 VM 전체 값이다. 각 조건 1회이므로 통계적으로 안정된 개선율은 아니다. worker가 같은 독립 단계도 약 16초 차이가 나 VM·네트워크·기동 편차가 포함된다. 4 workers에서 속도 개선이 관측되지 않았으므로 기본값 2를 유지한다. 54초 차이 전부가 worker 설정의 인과 효과라고 단정하지 않는다.

분리 실행의 각 job은 다음과 같다. 필수 `build`의 3초만 전체 CI 시간으로 보고하지 않는다.

| 분리 job | 시작→완료 | run 생성→해당 완료 |
| --- | --- | --- |
| 단위 테스트 | 174초 | 178초 |
| 독립 컴파일 | 117초 | 121초 |
| 앱 빌드 | 228초 | 232초 |
| Lint | 215초 | 219초 |
| 모든 결과를 확인하는 build | 3초 | **237초** |

분리는 기준보다 37초(약 13.5%) 짧았지만 목표 상한보다 87초 길다. 연장된 전체 job 시간 합은 737초로 단일 job 269초보다 크다. 기본 공개 환경이라 실행 과금은 추가하지 않았지만 더 많은 계산 자원을 쓴 절충이다. 효과에 비해 workflow·캐시 운영이 복잡해져 바로 기본값으로 채택하지 않았다.

기존 343초는 이전 제품 커밋·캐시 저장 포함 조건이다. 새 274초와 직접 비교해 worker 변경의 효과로 계산하지 않는다. 이번 측정의 캐시는 새 job 간에는 없지만, 같은 job에서 앞선 명령이 만든 resource 등의 로컬 task cache는 재사용할 수 있다. `FROM-CACHE`를 이전 실행의 복원이나 새 테스트 실행으로 해석하지 않는다.

## 확인한 병목과 해석

- 순차 worker 2의 실제 task 로그와 대조한 프로파일에서 `:app:mergeExtDexDebug` 약 48.7초, DS Lint 약 21.5초, DS 단위 테스트 약 18.9초가 관측됐다.
- 분리 앱 빌드는 명령만 211.9초였다. 초기 startup 약 29.8초·프로젝트 구성 약 29.7초, `mergeExtDexDebug` 약 57.7초 등이 포함됐다. 공통 설정 파일 정리만으로 앱 패키징·컴파일 비용이 사라지지 않는다.
- Gradle profile 표에는 프로젝트 합계·의존성 해석·개별 task가 함께 있다. 실행 로그의 실제 task 이름과 맞는 항목만 위 사례로 사용했다. 병렬 task 시간의 합은 경과 시간보다 길 수 있어 서로 합산해 전체 시간으로 계산하지 않았다.
- 기존 캐시 정리 16초는 이전 저장 포함 실행에서 확인한 별도 비용이다. 이번 실험은 저장하지 않아 그 비용이 없으며, 제거 효과를 worker 효과와 섞지 않았다. 기본 workflow의 정리 정책은 그대로다.
- 추가 개선을 적용할 때도 동일 제품·검사 합집합·빈 캐시 조건으로 검증해야 한다. warm cache를 준비한 뒤 cold 목표 달성으로 보고하거나 테스트/Lint/독립 격리를 제거하지 않는다.

## Now in Android의 Navigation 3 전환 전 기준

사용자가 지정한 [PR #1902](https://github.com/android/nowinandroid/pull/1902/commits)는 2025-12-03 병합됐다. PR의 첫 커밋 부모이자 base인 **`d438a007c4ba5794522d963d8c53732dfb5be6f8`**를 검토했다. 마지막 migration 커밋의 바로 앞 커밋이나 최신 main을 전환 전 코드로 대신 사용하지 않았다. Navigation 3·api/impl 모듈 분리는 이번 도입 대상이 아니다.

### 확인한 구조

- [root settings](https://github.com/android/nowinandroid/blob/d438a007c4ba5794522d963d8c53732dfb5be6f8/settings.gradle.kts)의 `pluginManagement.includeBuild("build-logic")`로 convention plugins를 공급한다.
- [build-logic README](https://github.com/android/nowinandroid/blob/d438a007c4ba5794522d963d8c53732dfb5be6f8/build-logic/README.md)는 반복 설정의 단일 원본과 작은 역할별 조합을 설명한다. 앱/라이브러리·Compose·Hilt·Room 등의 플러그인을 필요한 모듈에 선택 적용한다.
- [convention build](https://github.com/android/nowinandroid/blob/d438a007c4ba5794522d963d8c53732dfb5be6f8/build-logic/convention/build.gradle.kts)도 `kotlin-dsl`과 플러그인 컴파일 의존성을 사용한다. included build 자체의 첫 컴파일·다운로드 비용이 없어지는 구조는 아니다.
- [included build 설정](https://github.com/android/nowinandroid/blob/d438a007c4ba5794522d963d8c53732dfb5be6f8/build-logic/gradle.properties)에 캐시·병렬·configuration cache 설정이 별도로 있다. root 속성이 모두 자동 상속된다고 가정하지 않는다.
- [AndroidInstrumentedTests](https://github.com/android/nowinandroid/blob/d438a007c4ba5794522d963d8c53732dfb5be6f8/build-logic/convention/src/main/kotlin/com/google/samples/apps/nowinandroid/AndroidInstrumentedTests.kt)는 src/androidTest가 없는 라이브러리의 기기 테스트 구성을 비활성화한다. MONIT의 src/test Robolectric·단위 테스트를 끄는 근거가 아니다. variant별·생성된 테스트 소스까지 무시하는 조건으로 복사해서도 안 된다.

### MONIT에 대한 판단

**유지보수와 빌드 규칙 변경의 영향 범위 개선에는 적합하지만, 첫 실행 150초 달성은 아직 입증되지 않았다.** 현재 buildSrc는 SDK·minSdk·Java 버전 3개를 공유하는 작은 코드다. convention plugins를 만들면 그 코드를 컴파일하는 비용과 AGP 등의 컴파일 classpath가 추가될 수 있다.

[Gradle 공식 기준](https://docs.gradle.org/current/userguide/best_practices_structuring_builds.html#favor_build_logic_composite_builds_for_build_logic)은 included build를 권장하며 buildSrc보다 변경의 무효화 범위를 줄일 가능성을 설명한다. 다만 플러그인을 하나의 convention jar에 모두 넣고 모든 모듈이 사용하면 그 jar 변경의 전파는 남는다. 폴더 이름 변경만으로 첫 빌드 단축을 주장하지 않는다.

전환을 별도로 시험한다면 Android application/library 공통 설정과 두 모듈에서 쓰는 Compose 설정부터 최소한으로 묶는다. Hilt·Room·Robolectric·기기 테스트 조건은 필요한 모듈의 현재 동작을 보존한다. 한 모듈에만 필요한 설정을 형식 때문에 공통 플러그인으로 올리지 않는다.

참고 버전은 AGP 8.13.1·Kotlin 2.2.21이며 MONIT은 AGP 9.4.1·Compose compiler 플러그인 2.2.10을 사용한다. 참고 코드의 `org.jetbrains.kotlin.android`, JVM 11, SDK 35/23 설정을 복제하지 않는다. MONIT의 AGP 9 built-in Kotlin·JVM 17·compile/target SDK 36·minSdk 26과 현재 의존성·스키마·테스트를 보존해야 한다. [AGP 9의 DSL·Kotlin 변경](https://developer.android.com/build/releases/agp-9-0-0-release-notes)을 기준으로 호환성을 검증한다.

## 결론과 남은 범위

- 완료: 비용 확인·승인, 동일 제품의 worker 비교, 분리 실행 시험, 실제 프로파일 확인, 지정한 NiA 전환 전 소스 조사.
- 미달: 120초 목표와 150초 상한. 검증된 최소는 이번 조건에서 237초다. 더 빠른 실행 가능성을 부정하는 결과는 아니며, 현재 시험한 설정으로 달성했다고 주장하지 않는다.
- 미적용: worker 4, 분리 production workflow, 캐시 정리 변경, build-logic 전환. 실험 브랜치의 workflow는 병합하지 않는다. 이 문서 PR에는 결과 문서만 포함한다.
- 다음 판단: true cold와 main 캐시를 사용할 수 있는 새 PR 첫 실행의 목표를 구분하고, 실제 병목에 대응하는 별도 변경을 선정한다. build-logic은 독립 비교 대상으로 검증하며, 속도 근거 없이 큰 구조나 유료 실행 환경을 도입하지 않는다.
