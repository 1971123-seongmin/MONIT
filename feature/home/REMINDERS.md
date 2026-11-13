# 납입 알림 설정·재조정·수신

Issue #62의 구현 경계다. [월별 납입](STATE.md)의 확정 상태를 읽으며 거래·완료를 자동 생성하지 않는다. 실제 사용자 자료와 원격 서비스, 정확 알림 특수 권한, 무기한 재촉, 범용 스케줄 엔진은 포함하지 않는다.

## 사용자 설정과 시간 정책

홈의 알림 설정에서 전체 켜기/끄기, 실제 권한·앱·채널 상태와 기본 시점, 일정별 선호를 확인한다. 최초 전체 선호는 꺼짐이며 명시 켜기 전에는 OS 알림을 요청하지 않는다. 일정별 미설정 기본값은 켜짐으로 표시하지만 전체 선호와 OS 세 조건이 모두 허용돼야 유효하다. 전체 off/on은 일정별 선택을 덮어쓰지 않으며 OS 거부·철회도 사용자 선호를 바꾸지 않는다.

설정은 같은 값 재설정 시 추가 변경 없는 값 멱등 API다. 실제 변경에는 global/rule revision CAS를 적용한다. 다른 값의 오래된 요청을 덮어쓰지 않는다. 규칙별 선호·설정 revision·dirty는 같은 Room 트랜잭션이며 금융 revision·월항목·거래에는 영향을 주지 않는다.

기준은 Asia/Seoul이다. 예정일 전날·당일·다음 날 각각 09:00 이후의 세 단계만 계산하며 30일 고정 간격이나 무기한 일별 재촉을 사용하지 않는다. 매번 월의 실제 예정일과 말일/윤년 보정 값을 재사용한다. 활성 PENDING/RECONFIRM 중 시작·종료 월 범위에 있는 항목만 대상으로 삼는다. 완료·건너뜀·비활성·종료 이후 보존된 재확인 이력은 제외한다.

지연 시 `now - 24h < stageAt <= now`의 항목별 최신 단계 하나부터 고른 뒤 이미 처리한 논리 단계 `(itemId, stage)`를 제외한다. 오래된 단계로 되돌아가 발송하지 않는다. 요약은 앱 전체에서 서울 날짜당 하나다. 이미 처리한 날의 추가 대상·토글·재확인은 새 푸시를 만들지 않고 앱 목록에서 확인한다. 최근 범위를 벗어난 누락은 미완료 기록만 유지한다.

공통 안내는 “오전 9시 이후 알림을 요청합니다. 절전·기기 상태에 따라 늦거나 표시되지 않을 수 있으며, 정확한 시각은 보장하지 않습니다.”다. UI는 정확 시각·최대 지연·강제 종료/전원 꺼짐의 도착을 보장하지 않는다.

## Room과 OS의 경계

비파괴 schema 5→6은 `reminder_settings`, `reminder_rules`, `reminder_days`만 추가한다. 기존 금융·납입·제출·초안 열/원문/hash를 변경하지 않는다. 설정과 일별 요약은 장부/저장 세대에 귀속되며 규칙 선호는 실제 같은 장부 규칙에 FK를 둔다. 고아 선호와 다른 세대는 거부한다.

금융·납입 writer는 기존 금융 commit 안에서 **dirty만** 함께 기록한다. OS 호출을 금융 트랜잭션에 넣거나 금융 writer에서 호출하지 않는다. 계좌/거래·규칙·회차의 ledger revision과 설정 revision을 관찰하는 프로세스 범위 Coordinator가 주입된 기존 ioScope에서 처리한다. UI ViewModel은 entry viewModelScope를 사용한다.

Coordinator는 현재 DB에서 후보를 읽고 원자적으로 일별 요약 token/원래 멤버와 재조정 token을 저장한다. 앱 전역의 단일 부정확 `AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP)` 일회 예약을 다음 필요한 날짜에 건다. 수신 시 원래 context·세대·현재 token·예정 시각을 다시 검증한다. 같은 PendingIntent의 extras가 갱신되거나 오래된 알람이 들어와도 현재 DB의 실제 시간과 대상부터 재확인한다.

OS 적용 직전에도 설정/ledger revision·token 및 실제 알림 접근을 확인한다. 계획 이후 변경되면 다시 계산하며 지속 경합은 dirty로 남긴다. 발송 뒤 clean CAS가 실패해도 이미 사용한 일별 token을 새 발송으로 치환하지 않는다. OS 실패·준비 단계 오류는 failed/dirty로 표시하고 원래 금융 자료를 보존한다. 취소를 성공으로 삼키지 않는다.

`reminder_days`는 날짜당 원래 token과 멤버를 보존한다. PENDING은 같은 notification ID로 재시도하고 POSTED는 일반 재조정으로 다시 게시하지 않는다. 대상이 사라진 경우 취소한다. 잠금화면과 일반 문구에 계좌·금액·일정 이름을 넣지 않으며 통지에는 최근 유효 단계의 남은 24시간 범위에 맞춘 timeout을 둔다.

**DB commit과 OS 예약/게시·사용자 표시 사이에는 원자 트랜잭션이 없다.** dirty·세대·revision·token·동일 notification ID와 onlyAlertOnce로 중복/오래된 요청을 줄이지만 exactly-once, 절전 중 실행, 실제 표시 시점은 보장하지 않는다. OS 게시 뒤 DB 기록 전 중단 같은 간극은 남으며 다음 재조정에서 같은 ID로 재검증한다. 알림 오류가 금융 rollback이나 자동 완료를 만들지는 않는다.

앱 시작/복귀, credential storage를 사용할 수 있는 재부팅 후 BOOT_COMPLETED, 시간/시간대 변경, 앱 갱신에 재조정한다. USER_UNLOCKED를 manifest 수신으로 가장하지 않으며 잠긴 저장소에서 direct-boot DB를 만들지 않는다. 향후 복원 commit 뒤 `afterRestore(context)`로 영속 선호를 새 세대에 결합하고 dirty로 재구성한다. 백업은 사용자 전체/규칙 선호를 포함하되 OS 권한·예약·runtime token·일별 게시 상태를 기기 권한처럼 복원하지 않아야 한다. 실제 백업/복원 구현은 후속 범위다.

## 권한·조회 진입과 UI

POST_NOTIFICATIONS와 RECEIVE_BOOT_COMPLETED만 선언하고 정확 알림 특수 권한은 요청하지 않는다. 알람·시스템 Receiver는 exported=false이며 시스템 Receiver는 보호된 boot/time/package 행동만 수용한다. 알람/클릭 PendingIntent는 명시 대상과 FLAG_IMMUTABLE을 사용한다. 임의 nested Intent·URI 위임을 전달하거나 PendingIntent 생성자 ID를 발신자 인증으로 사용하지 않는다.

Android 13 이상 권한 요청은 명시 버튼에서만 실행한다. 필요 시 취소 가능한 rationale을 보여주고 반복 거부는 설정 경로로 안내한다. 매번 실제 권한을 확인하며 설정 복귀는 재조회이지 허용 성공이 아니다. 권한·앱·채널 차단을 구분하며 차단 중에도 금융 입력과 수동 확인은 계속 가능하다.

알림 클릭은 persisted token을 현재 장부/세대에서 조회해 원래 월/회차 조회로만 이동한다. 형식 오류·다른 action·외부 URI·grant flags는 거부한다. 기존 입력 화면을 폐기하지 않고 top-level 복귀까지 보류한다. 요청 소비는 해당 token으로만 확인해 늦은 이전 응답이 새 클릭을 지우지 않도록 한다. 없는 회차는 해당 월 목록, 읽기 실패는 재확인으로 분리한다.

설정은 MVVM과 순수 UiState/별도 진행 State다. DS Badge/Button/Card·기존 Nav2를 재사용하고 긴 내용·큰 글꼴은 안정적인 key를 가진 LazyColumn으로 표시한다. 시각 기준은 [D01 상태 보드](https://www.figma.com/design/7jkAht5c7RtQ0DkZ9QFxXw?node-id=345-222)이며 시스템 알림 아이콘은 Android 플랫폼 리소스다.

## 검증과 남은 경계

순수 모델 시험은 서울 09시/윤년/최근24h/최신 단계/제외를, 실제 파일 Room 시험은 일별 경합·재열기·전체/규칙 선호·오류/dirty·세대·기존 자료 보존 Migration을 검사한다. OS 프로토콜/Android shadow 시험은 부정확 일회·불변 Intent·개인정보 문구·Android13 거부/철회를, Route/VM/Nav2 시험은 설정 복귀·연타·실패·원래 월 조회·입력 원문 보존과 Light/Dark/320dp·큰 글꼴을 검사한다. 프로토콜 대역을 실제 금융·OS 도착 증거로 표현하지 않는다.

사용자 지시로 S23 조회·설치·권한 조작·실제 도착/재부팅/절전·시스템 IME 시험은 저녁 연결 후로 보류했다. 호스트 테스트·Native Canvas·Preview 컴파일은 실제 기기의 알림 도착 또는 release 성능 검증을 대신하지 않는다. 장기 자료량에서의 계획 계산·8초 receiver 실행 한계는 별도 기기/규모 검증이 필요하다.

플랫폼 기준은 [알람](https://developer.android.com/develop/background-work/services/alarms), [알림 권한](https://developer.android.com/develop/ui/compose/notifications/notification-permission), [알림 Activity 진입](https://developer.android.com/develop/ui/views/notifications/navigation), [implicit broadcast 예외](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions), [USER_UNLOCKED 제한](https://developer.android.com/reference/android/content/Intent#ACTION_USER_UNLOCKED)을 확인했다.
