# 암호화 파일 백업과 전체 복원

Issue #66. 금융 데이터는 현재 Room 인스턴스에서 논리적으로 교체한다. DB 파일을 닫고 덮어쓰거나 앱 데이터를 초기화하지 않는다. `RoomBackupRepository`는 파일·UI·Keystore를 직접 호출하지 않고, 앱 계층이 SAF와 기기 전용 복구 사본을 연결한다.

## 형식과 검증 순서

휴대 파일의 envelopeVersion=1, cryptoSuite=1이다. big-endian 52바이트 헤더는 magic `MONITBK1`(8), version(2), suite(2), PBKDF2 반복 수(4), salt(16), nonce(12), 암호문+tag 길이(8) 순서다. 원본 헤더 전체를 AAD로 인증한다. PBKDF2WithHmacSHA256 600,000회로 256비트 키를 만들고 AES-256-GCM, 128비트 tag를 사용한다. salt와 nonce는 파일마다 새로 생성한다. 공급자를 임의 고정하지 않는다.

암호는 15~128 Unicode code point, UTF-8 512바이트 이하이며 제어 문자·NUL·고립 surrogate를 거부한다. 공백 제거·정규화·대소문자 변환을 하지 않는다. JCA 비밀번호 처리의 UTF-8 호환성은 별도 Node/OpenSSL로 생성한 한글·결합문자·비BMP golden vector와 비교한다. `Generate-BackupVectors.cjs`는 합성 자료만 생성한다.

1. 실제 파일 바이트 상한, magic/version/suite, 고정 KDF 값과 길이를 검사한다. 공격자가 반복 수를 높여 KDF 작업을 유도하지 못한다.
2. 암호문 전체의 GCM 인증을 완료한다. 이 단계 전 JSON 파싱·미리보기·DB 쓰기를 하지 않는다.
3. strict UTF-8, 중복 키, 깊이·token 수·JSON 문법을 검사한다.
4. 고정된 bundled schema와 manifest, ID·FK·정수·날짜·금융 계산·이력 연속성·원래 요청/영수증 관계를 검사한다.

전체 평문은 8MiB, 외부 파일은 8MiB+68바이트, 원문 합계는 2MiB/32개, 업무·근거 레코드는 50,000개, JSON 중첩은 16단계, scalar token은 1,000,000개 이하이다. 제공자가 신고한 크기만 신뢰하지 않는다. KDF와 논리 검증은 주입된 Default dispatcher, 파일 작업은 IO dispatcher에서 수행한다.

KDF/암호가 10초를 넘으면 지연과 취소 요청을 안내한다. 전체 파싱·논리 검증은 30초, 외부 파일 읽기/쓰기와 내부 복구 사본은 60초에 협력 중단을 요청한다. 이는 동기 JCA·provider·close가 그 시각에 반드시 종료된다는 보장이 아니다. UI는 취소 요청과 실제 종료를 구분하고 비복귀 상태에서도 대기 안내를 유지한다. `CancellationSignal.cancel`과 descriptor 정리는 IO child에서 수행하며 부모 작업은 실제 정리 종료까지 기다린다. 프로세스 범위의 백업 전용 단일 lease는 VM이 교체되거나 취소되어도 실제 Job 완료 후에만 풀린다. 늦은 단계/성공을 UI에 반영하거나 그 전에 새 KDF/파일/복원을 시작하지 않는다. 쓰기 시작 후 취소는 외부 파일 검증 미완료로, 복원 취소/응답 유실은 영속 완료 표식 조회로 이어지며 금융 교체를 자동 재실행하지 않는다.

## 논리 v2와 이전 v1

실행 schema 원본은 [v2 JSON Schema](src/main/resources/backup/backup-v2.schema.json)다. `formatVersion=2`, `contractVersion=2`를 사용하고 `manifest`에 실제 배열별 건수·원문 크기/hash·제외 항목·기록 여부를 검증한다. 금융 9개 테이블의 DTO는 검토된 Room schema7 필드에 고정한다. [생성 스크립트](../../scripts/Generate-BackupSchema.cjs)는 최신 DB를 임의 추종하지 않는다.

`submissionsEvidence`는 원래 `sourceContext`와 operationId 단위로 canonical v1/v2/v3/v4 요청·hash·영수증·성공/거절·참조 존재 여부·납입 intent 상태를 보존한다. 저장소의 새 `storageGeneration`으로 원래 문자열을 재작성하지 않는다. READY/CONSUMED/RELEASED 모두 조회·재백업 근거이며 복원 후 실행 대기열로 옮기지 않는다. UI 초안 payload는 제외하고 원래 draftId/revision/scope와 `EXCLUDED_BY_POLICY`만 보존한다.

복원 근거는 별도 `restored_evidence` 테이블에 저장한다. pending/prepared/retry 경로는 이 테이블을 실행하지 않는다. `operation_fences`는 incoming UUID와 현재 장부에서 사용한 UUID를 보존하며 전체 교체에서도 삭제하지 않는다. 참조가 없었던 과거 요청에 참조 행을 새로 만들지 않는다.

[엄격한 v1 schema](src/main/resources/backup/backup-v1.schema.json)는 유지한다. 현재 모델에서 손실 없이 표현 가능한 baseline 계좌·기준액·초기 거래만 지원하며, 원래 v1 bytes 전체를 원문으로 보존한다. 현재 대응할 수 없는 분류/결제수단/이관 관계/납입/변경 이력이 있거나 초기 revision이 아닌 자료는 **전체 거부**한다. 일부를 버리고 복원하지 않는다. 원문 자체가 2MiB 제한을 넘으면 거부한다. v1에 없던 제출 근거·알림 선호는 `NOT_RECORDED_BY_V1`이며 빈 배열/false로 기록됐다고 주장하지 않는다.

알림의 영구 선호는 포함하지만 기기 권한·토큰·OS 예약·당일 통지 큐·캐시는 제외한다. source mapping이 없는 현재 모델의 상태도 manifest에 명시한다. 제외된 초안을 복원하거나 실행 가능한 가짜 명령을 생성하지 않는다.

## 내보내기와 복원 원자성

SAF CreateDocument/OpenDocument로 사용자가 위치를 선택한다. URI 접근 권한·암호·파일 내용은 SavedStateHandle/초안/로그에 남기지 않는다. 암호 입력은 비저장 메모리이며 제출 후 비운다. JVM의 불변 문자열과 GC 복제본까지 물리적으로 소거됐다고 보장하지 않는다. 입력 흐름은 FLAG_SECURE를 사용하며 기존 flag 상태를 보존한다.

내보내기는 일관된 Room read snapshot → 암호화 → 외부 파일 쓰기/flush/close → 새 descriptor 재읽기 → 인증·논리 검증·정확한 파일 hash 대조 순서다. 이 전체 과정이 성공한 경우에만 마지막 검증 시각을 저장한다. 재읽기 실패 시 내보내기/검증 미완료를 표시하고 이전 성공 시각을 유지한다. 이전 백업을 자동 삭제하지 않는다.

복원은 인증·검증 → 생성일/건수/제외 범위와 전체 대체 미리보기 → 명시 최종 확인 순서다. 파일 선택이나 미리보기만으로 교체하지 않는다. 저장소는 한 작업만 실행하며 미리보기의 현재 revision/generation을 다시 확인한다.

유지보수 guard를 켜고 선행 writer가 끝난 DB barrier 뒤 현재 전체 snapshot을 얻는다. 모든 금융·초안·알림 writer 및 Receiver의 DB 진입은 guard를 확인한다. 이 임시 경계에서는 OS의 기존 예약을 취소하지 않으며, 성공/rollback 모두 guard 해제 신호가 보류한 알림 준비를 다시 요청한다. 파일 처리·암호화·사용자 대기는 금융 교체 트랜잭션 밖이다. 검증된 복구 사본을 얻은 뒤 최종 revision/generation을 재검사하고, 같은 Room의 **한 트랜잭션** 안에서 기존 논리 행 삭제/삽입, 새 generation, 이전 UUID fence, 복원 완료 표식을 함께 커밋한다.

커밋 전 취소·오류는 기존 장부를 유지한다. 커밋 뒤 응답만 잃으면 완료 표식을 조회하며 같은 복원을 재실행하지 않는다. 앱 재시작 때 미확인 완료 표식을 먼저 표시한다. 완료 확인 후 장부를 다시 읽고 generation별 탐색 상태를 새로 만들어 이전 입력 화면을 재사용하지 않는다.

알림은 커밋 뒤 별도 재조정한다. 실패해도 금융 복원은 성공 상태를 유지하며 알림 재설정만 재시도한다. 영속 dirty/failed 해제 확인 후에만 알림 준비 결과를 갱신한다. OS 표시·정확한 전달 시각을 보장하지 않는다.

## 기기 전용 복구 사본

`KeystoreRecoveryCopyStore`는 앱 내부 디렉터리에 AndroidKeyStore의 비추출 AES-256-GCM 키로 암호화한 사본을 만든다. 휴대용 암호 파일과 다른 `MONITRC1` 형식이며 type/ledger/generation/nonce/길이를 AAD로 인증한다. 암호문 쓰기·fd sync·close·재읽기·복호화 byte 대조 후 검증된 현재 사본 하나를 원자적으로 교체하고 디렉터리를 sync한다. 실패한 암호화 후보를 자동 삭제하지 않으며 평문 fallback은 없다. 키 생성·공간·동기화·재읽기 실패는 금융 교체를 막는다.

이 사본은 앱 삭제·키 유실 후 휴대 복구용으로 쓸 수 없다. 사용자가 보관하는 비밀번호 백업을 대신하지 않는다. 실제 키의 하드웨어 보호 여부는 기기 구현에 달려 있다. [Android 암호화 권장 사항](https://developer.android.com/privacy-and-security/cryptography)과 [Android Keystore](https://developer.android.com/privacy-and-security/keystore)의 플랫폼 경계를 따른다.

## 검증 범위와 기기 인계

host 시험은 고정 JCA/OpenSSL vector, 변조/한도/strict 형식, 실제 파일 Room의 원자성·CAS·세대·마이그레이션, stream 실패, ViewModel의 명시 확인/결과 유실/알림 실패, Compose 화면을 검증한다. 일반 SecretKeySpec 기반 내부 사본 시험은 AndroidKeyStore 성공 증거가 아니다.

실제 OEM SAF의 close/재읽기·권한 회수, 실제 Keystore 생성/잠금/디스크 오류, 프로세스 강제 종료/재시작, 키보드·스크린샷 차단·큰 글씨·실제 알림 동작은 기기 검증 항목이다. 사용자 요청에 따라 기기 조회·설치·테스트는 저녁까지 보류한다. 실제 개인 금융 자료는 시험·로그에 사용하지 않는다. [SAF 문서](https://developer.android.com/training/data-storage/shared/documents-files)와 실제 provider 동작을 함께 확인해야 한다.
