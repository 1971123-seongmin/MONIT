# 이관 검토 JSON v1

원본을 쓰지 않는 #56 미리보기의 입력 형식이다. 일반 Notion CSV/REST 응답을 자동 지원하지 않는다. [합성 예시](fixtures/import-preview-v1-synthetic.json)만 Git에 포함하며 실제 금융 자료는 이 위치에 저장하지 않는다. 읽기·판정·실제 반영의 경계는 [구현 계획](../architecture/import-preview-plan.md)을 따른다.

## 원문 계층과 digest

envelope는 `version: 1`, `snapshotJson`, `snapshotSha256`, `mappingJson`, `mappingSha256` 다섯 필드다. 두 Json 필드는 객체가 아닌 정확한 문자열이다. 각각의 변경 없는 UTF-8 bytes에 대한 SHA256을 소문자 hex 64자리로 제공한다. trim·키 재정렬·재직렬화·줄바꿈/Unicode 정규화 후 hash를 계산하지 않는다. envelope 전체 bytes의 SHA256도 앱에서 별도로 계산한다.

이 문자열은 **제공된 중간 검토 표현**이다. hash는 그 표현의 무결성만 검사하며 최초 Notion 응답 bytes의 보존·완전성·출처 진위 인증을 의미하지 않는다. 별도로 보관한 원문 MCP/SQL/REST 추출본과 혼동하지 않는다. 임시 content URI의 세션 내 읽기는 원문 영속 보관이나 재시작 복구가 아니다.

## snapshotJson

| 필드 | 형식과 의미 |
| --- | --- |
| capturedAt | ISO Instant 문자열. 제공된 추출 시각이며 원본 서버 조회의 진위 보증이 아님 |
| scope | 제공된 범위 설명, 1~256 코드단위 문자열 |
| sources | 출처별 `namespace`, `entityKind`, `paginationComplete`, `missingProperties` 목록 |
| rows | 아래 원본 행 목록. 최대 10,000행 |

sources의 paginationComplete는 true/false/null 또는 누락이다. missingProperties는 미확보 속성명 문자열 배열/null/누락이며, 누락을 빈 배열로 바꾸지 않는다. 둘 중 미확인이 있으면 UNKNOWN/PARTIAL이다. 제공된 모든 완료 표시가 있어도 Notion 전체 자료 확보·전체 금액 대사 검증은 별도다. 거래 행의 관계 미확인도 부분 범위로 표시한다.

원본 행 필드:

- `sourceNamespace`, `entityKind`: 안정적인 데이터소스 출처와 행 종류. namespace를 파일명·연도 제목으로 만들지 않는다.
- `sourceId`: 원본 행 ID 문자열 또는 null/누락. 자동 중복 키는 `(sourceNamespace, entityKind, sourceId)` 전체다.
- `locator`: 추출본 안에서 고유한 행 위치. ID가 없으면 snapshot digest와 locator로 이 추출본에서만 추적한다.
- `rawJson`: 변환할 행의 정확한 중간 JSON 문자열. 원문 속성명·관계 ID를 유지한다.
- `rawSha256`: 위 문자열의 정확한 UTF-8 bytes SHA256. 불일치는 해당 행 보류다.
- `relationsComplete`: 관계 ID 확보 여부, boolean/null/누락. 미확인을 빈 관계로 만들지 않는다.
- `relationNamespace`: 관계 ID의 실제 Wallet 출처. null/누락 또는 매핑의 walletNamespace와 다르면 거래 후보로 만들지 않는다.

형식이 식별되는 개별 rawJson의 해석 실패·중복 키·digest 오류는 그 행을 보류한다. 동일 원본 키의 반복/상충은 양쪽 보류다. namespace/entityKind가 다른 같은 ID는 같은 원본으로 취급하지 않는다. locator 중복 등 행 추적 자체가 불명확하면 파일 오류다.

## mappingJson

| 필드 | 형식과 의미 |
| --- | --- |
| version | 명시적인 매핑 version 문자열 |
| transformerVersion | 현재 `monit-preview-1`만 지원 |
| target | 현재 대상 `ledgerId`, `storageGeneration` UUID 또는 null/누락. 미확정이면 모든 계좌 매핑을 미확인으로 보류하며 다른 장부로 자동 보정하지 않음 |
| fields | 출처별 속성 대응, 아래 필드 모두 필요 |
| accounts | `walletNamespace`, `sourceId`, `accountId`, `confirmed` 목록 |
| choices | `locator`, `decision`, `confirmed` 목록. decision은 HELD/EXCLUDED만 지원 |

fields의 출처 키는 namespace/entityKind다. `title`, `date`, `amount`, `category`, `debitRelation`, `creditRelation`은 서로 다른 실제 원문 속성명이다. `walletNamespace`는 명시한 Wallet 출처, `currency`는 명시 통화 문자열 또는 null/누락, `currencyConfirmed`는 명시 확인 boolean이다. 대응이 중복/상충하면 마지막 값 우선으로 고르지 않는다. 카드 이름·연결 계좌·won 표시·초기자산·수식 잔액에서 통화/계좌/확인 잔액을 추정하지 않는다.

현재 transaction 행의 rawJson은 평면 JSON 속성만 지원한다.

- title/date/category는 문자열이다. date는 YYYY-MM-DD, 서울 기준 1900-01-01부터 검토일 오늘까지다.
- category는 `수입`, `지출`, `이체`만 지원한다. 환전/카드 정산/통계/미지원 구조는 보류 또는 명시 확인한 제외다.
- amount는 1~999,999,999,999의 정수 원화 문자열 또는 JSON 정수다. 소수·지수·음수·0·범위초과를 보정하지 않는다. rawJson은 원문 그대로 남는다.
- debitRelation/creditRelation은 중복 없는 원본 Wallet ID 배열이다. 관계 없음은 확보된 명시 빈 배열이며 속성 누락/null과 다르다. 복수/중복은 보류다.
- 수입은 credit 하나와 빈 debit, 지출은 debit 하나와 빈 credit, 이체는 양쪽 하나씩 서로 다른 계좌가 필요하다. 두 원본 행을 임의로 짝맞추지 않는다. 쌍 정규화 계약이 없는 한쪽 이체는 보류다.
- 명시 확인된 KRW와 현재 장부의 활성 계좌 대응만 변환 후보를 만든다. 계좌를 새로 생성하거나 미확인 잔액을 0원으로 만들지 않는다.

accounts는 정확한 Wallet namespace/ID로 조회한다. 중복 대응·미확인 confirmed·없는/보관 계좌는 보류다. choices의 제외는 confirmed=true일 때만 인정하며 원문을 삭제하지 않는다. 같은 locator의 상충 결정, 다른 locator의 결정은 매핑 오류다.

대상 장부를 아직 확인하지 못한 준비 파일은 target:null/accounts:[]로 제공할 수 있다. 원문과 보류 항목은 표시하지만 금융 후보를 만들지 않는다. 현재 앱 context/revision은 결과의 비교 근거로만 보존하고 원본 파일에 주입하거나 명시 매핑으로 취급하지 않는다. 알려진 외부 target은 현재 장부에 맞춰 덮어쓰지 않고 파일을 차단한다.

## 안전 한도와 결과

검토 전용 입력 한도는 실제 읽은 envelope **8 MiB**, 원본 10,000행, 출처/fields 각각 64개, accounts/choices 각각 10,000개다. 각 JSON 층은 깊이 16·값 노드 200,000개·키 256 UTF-16 코드단위 한도를 검사한다. 일반 scalar는 1,048,576 UTF-16 코드단위, envelope 내부 문자열은 전체 입력 bytes 한도 안에서 허용한다. 한도 초과를 절단한 성공으로 바꾸지 않는다. 이는 백업이나 개별 금융 요청 크기 계약이 아닌 미리보기의 용량 제한이다. 직렬화 rawJson에 업무 입력용 16KiB 한도를 재사용하지 않는다.

모든 JSON 층에서 객체 생성 전에 중복 키를 검사한다. 상위 JSON·UTF-8·전체 digest 오류는 파일 실패이며 전체 행 수는 확인 불가다. 개별 식별 행의 원문 오류는 해당 행 보류다. 문자열 원문에 포함된 Unicode·줄바꿈은 변경하지 않는다.

`제공된 원본 행 = 반영예정 후보 + 기반영 + 보류 + 제외`를 상호 배타로 표시한다. 유사 중복·변환 불가·관계 누락은 보류 사유이며 중복 집계하지 않는다. schema3에는 source mapping이 없어 기반영 확정은 0·중복 검증은 UNAVAILABLE이다. 로컬/파일 내 유사 거래는 보류 후보이며 자동 합치지 않는다.

후보의 월·종류별 KRW 정수 금액과 계좌 효과는 BigInteger로 합산한다. 보류·미지원·통화 미확인·제외 원문은 이 합계에 포함하지 않고 누락 행 수를 명시한다. 외화를 0원으로 간주하거나 KRW와 더하지 않는다. 이체는 수입/지출과 분리하며 계좌 효과는 확인 잔액이 아니다. 이 부분 집계는 전체 금액 대사 성공이 아니다.

예상 거래 수와 실제 생성 수를 구분하고 실제 생성은 항상 0이다. 원본/mapping digest·변환 version·대상 ledger revision·판정을 묶은 fingerprint는 변경 감지용이며 반영 승인 토큰이 아니다. 파일 bytes나 장부 revision 변경·재확인 실패는 재검토가 필요하다. 금융 반영 API/버튼·새 Migration·source mapping 저장은 이번 버전에 없다.
