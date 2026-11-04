# paypal-sandbox

주문 결제 흐름에서 PG 호출을 DB 트랜잭션 안에 넣을 때와 밖으로 뺄 때 무엇이 달라지는지 직접 장애를 넣어 보며 비교하는 실험 프로젝트입니다. PG는 PayPal 샌드박스와 엑심베이 테스트 서버를 붙였습니다. 메모리 안에서 도는 가짜 PG는 자동 테스트에서만 씁니다.

Spring Boot 4.1, Java 25, MySQL 8.4를 씁니다. 실행과 테스트 모두 Docker로 MySQL을 띄우므로 Docker가 필요합니다.

## 두 구조

**구조 A. 트랜잭션 안** (`InTransactionCheckout`)

하나의 `@Transactional` 메서드 안에서 주문과 상품 행을 잠그고, 재고를 줄이고, PG 캡처를 부르고, 주문을 PAID로 바꿉니다. 코드가 짧고 상태도 적습니다.

**구조 B. 트랜잭션 분리** (`SplitTransactionCheckout`)

1. 짧은 트랜잭션으로 주문을 `PAYMENT_IN_PROGRESS`로 바꾸고 재고를 예약한 뒤 커밋합니다.
2. 트랜잭션 없이 PG 캡처를 부릅니다. 멱등 키는 `capture-{주문ID}`로 고정합니다.
3. 짧은 트랜잭션으로 주문을 PAID로 바꾸고 결제 기록과 outbox 이벤트를 남깁니다.

PG가 거절하면 주문을 실패로 바꾸고 재고를 돌려줍니다. PG가 타임아웃 나면 실패로 단정하지 않고 `PAYMENT_UNKNOWN`으로 남깁니다. 2번과 3번 사이에서 무슨 일이 생겨도 주문에 흔적이 남고, 대사(`PaymentReconciler`)가 PG를 조회해서 상태를 맞춥니다.

## 같은 장애를 넣었을 때 결과

| 장애 | 구조 A (트랜잭션 안) | 구조 B (분리) |
|---|---|---|
| PG 캡처 뒤 DB 반영 실패 | 롤백되어 주문은 CREATED. PG에서는 돈이 빠져나감 | 주문이 PAYMENT_IN_PROGRESS로 남고 대사가 PAID로 확정 |
| PG가 결제는 했는데 응답 타임아웃 | 롤백되어 CREATED. 결제 여부를 모름 | PAYMENT_UNKNOWN으로 남고 대사가 PAID로 확정 |
| PG 응답 지연 + 동시 결제 | 결제 건마다 커넥션과 행 락을 쥐고 기다림. 풀이 고갈되면 상관없는 조회도 실패 | PG를 기다리는 동안 커넥션을 반납 |

구조 A에서 롤백된 주문은 결제를 시도하지 않은 주문과 상태가 똑같이 CREATED입니다. 그래서 대사 대상으로 골라낼 수 없고, 미결제 주문을 모두 PG에 조회해 봐야(`/diagnostics/mismatches`) 찾을 수 있습니다. 트랜잭션으로 묶으면 불일치가 없어지는 게 아니라 보이지 않게 됩니다.

이 결과는 `src/test`의 테스트로 재현됩니다. 테스트는 외부 PG 대신 `src/test`에 있는 가짜 PG(`FakePaymentGateway`)로 돌고, DB는 Testcontainers로 띄운 MySQL을 씁니다.

```
./gradlew test
```

- `CheckoutScenarioTest`: 장애별로 두 구조를 비교합니다. 멱등 캡처, 실패 주문의 환불(보상)도 확인합니다.
- `ConnectionPoolExhaustionTest`: 커넥션 풀 2개, PG 지연 1.5초에서 결제 2건을 동시에 돌리고 그 사이에 상품 조회가 되는지 봅니다.

## 화면

`./gradlew bootRun` 후 http://localhost:8080 을 엽니다.

- **쇼핑 화면** (`/`): 상품 목록 → 장바구니 → 주문서 → PG 승인 → 결제 완료 화면 순서로 진행합니다. 주문서의 "실험 설정"에서 결제 처리 구조(트랜잭션 안, 분리)를 고릅니다. 결제 완료 화면 아래 "실험 정보"에 우리 주문 상태와 PG 실제 상태를 나란히 보여 줍니다.
- **실험 콘솔** (`/lab.html`): 장애 주입, 주문별 캡처와 대사, 불일치 진단을 다룹니다.

결제 수단은 엑심베이와 PayPal 중에서 고릅니다. 엑심베이는 공용 테스트 가맹점이 기본값이라 설정 없이 나타나고, PayPal은 키를 넣어야 나타납니다. 다만 엑심베이는 아래 "엑심베이" 항목에 적은 이유로 지금 결제를 끝까지 진행할 수 없어서, 실제로 돌려 볼 수 있는 쪽은 PayPal입니다.

## 실행

```
./gradlew bootRun
```

`compose.yaml`의 MySQL이 함께 뜹니다. 앱을 꺼도 컨테이너는 남아 있고, 테이블은 실행할 때마다 새로 만듭니다. 호스트 포트는 Docker가 빈 포트로 고르며 `docker compose port mysql 3306`으로 확인합니다. 접속 계정은 `sandbox` / `sandbox`, DB는 `paypal_sandbox`입니다. 직접 띄운 MySQL을 쓰려면 `SPRING_DOCKER_COMPOSE_ENABLED=false`와 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 넘깁니다.

키를 하나도 넣지 않으면 결제 수단에 엑심베이만 나옵니다. PayPal을 쓰려면 아래처럼 키를 넣고 실행합니다.

### PayPal 샌드박스

1. [developer.paypal.com](https://developer.paypal.com)에 로그인해서 샌드박스 앱을 만들고 Client ID와 Secret을 받습니다. 사업자 계정이 없어도 됩니다.
2. 프로젝트 루트에 `.env`를 만들고 키를 넣습니다. `.env`는 `.gitignore`에 들어 있습니다.

```
PG_PROVIDER=paypal
PAYPAL_CLIENT_ID=...
PAYPAL_CLIENT_SECRET=...
```

```bash
set -a; . ./.env; set +a; ./gradlew bootRun
```

3. 쇼핑 화면에서 결제 수단을 PayPal로 고르고 결제하면 PayPal 샌드박스 승인 화면으로 넘어갑니다. **Personal(구매자) 테스트 계정**으로 로그인해서 승인합니다. 판매자(Business) 계정으로 로그인하면 자기 자신에게 결제하는 셈이라 PayPal이 막습니다. 판매자 로그인이 남아 있으면 시크릿 창을 쓰세요.
4. 승인하면 `/checkout-return.html`로 돌아와서 고른 구조로 캡처하고 결제 완료 화면으로 넘어갑니다. 장애 주입은 실험 콘솔에서 켜 두면 실제 PG 연동에도 똑같이 걸립니다.

웹훅까지 받아 보려면 ngrok 같은 터널로 `/webhooks/paypal`을 외부에 열고, PayPal 대시보드에 웹훅을 등록한 뒤 `PAYPAL_WEBHOOK_ID`를 넘깁니다. `PAYMENT.CAPTURE.COMPLETED`가 오면 해당 주문을 대사합니다. 웹훅 내용을 그대로 믿지 않고 PayPal 주문을 다시 조회해서 판단합니다.

### 엑심베이

키 설정 없이 `./gradlew bootRun`만 해도 엑심베이 개발자 문서에 공개된 공용 테스트 가맹점(MID `1849705C64`)으로 붙습니다. 전용 테스트 가맹점을 받았다면 `EXIMBAY_MID`, `EXIMBAY_API_KEY`로 바꿉니다.

엑심베이 인증 결제창(PAYER_AUTH)을 PayPal과 같은 모양으로 맞췄습니다.

| 단계 | PayPal | 엑심베이 |
|---|---|---|
| 주문 생성 | Orders API로 주문 생성 | 결제 준비 API(`/v1/payments/ready`)로 fgkey 발급 |
| 구매자 승인 | PayPal 승인 화면 | SDK 결제창에서 카드 인증, `return_url`로 결과 수신 후 결제 검증 API(`/v1/payments/verify`) 호출 |
| 캡처 | capture API (`PayPal-Request-Id`로 멱등) | 승인 API(`/v1/payments/confirm`, PAYMENT_PA) |
| 대사 | 주문 조회 | 결제 조회 API(`/v1/payments/retrieve`) |
| 환불 | refund API | 취소 API(`/v1/payments/{transaction_id}/cancel`) |

**지금 상태.** 2026년 11월 기준으로 공용 테스트 가맹점의 결제창이 `Invalid fgkey(VE00)`을 냅니다. 결제 준비 API는 정상으로 fgkey를 주는데, 그 값을 SDK에 넘기면 결제창이 거부합니다. 엑심베이 문서의 예제 요청을 그대로 보내도 같아서 이 프로젝트 쪽 문제로 보이지 않습니다. 그래서 카드 인증 뒤 검증, 승인, 조회, 취소는 실제 결제 건으로 확인하지 못했습니다. 전용 테스트 가맹점을 받으면 다시 확인할 예정입니다.

`status_url`은 엑심베이 서버가 직접 부르는 주소라 localhost로는 받을 수 없습니다. 받아 보려면 터널 주소를 `EXIMBAY_STATUS_URL`로 넘깁니다.

## API

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/products` | 상품 목록. 결제와 상관없는 조회 |
| POST | `/checkout` | 장바구니 상품으로 주문 생성 + PG 주문 생성. 결제 처리 구조를 함께 받음 |
| POST | `/checkout/complete` | PG 승인 후 호출. 고른 구조로 캡처 |
| GET | `/config` | 쓸 수 있는 PG, 기본 PG, 커넥션 풀 설정 |
| POST | `/orders` | 주문 생성 + PG 주문 생성 (실험 콘솔용). `pg`에 `eximbay` 또는 `paypal` |
| POST | `/orders/{id}/capture?strategy=in-tx\|split` | 캡처 |
| GET | `/orders/{id}` | 주문 조회 |
| POST | `/reconcile` | `reconcile.stale-after`(기본 30초)가 지난 결제 중 주문을 모두 대사 |
| POST | `/reconcile/{id}` | 주문 하나를 바로 대사 |
| GET | `/diagnostics/mismatches` | PG는 캡처됐는데 주문은 결제 완료가 아닌 건 |
| GET, PUT, DELETE | `/faults` | 장애 주입 조회, 설정, 초기화 |
| GET | `/eximbay/params/{pgOrderId}` | 엑심베이 SDK에 넘길 값 |
| GET, POST | `/eximbay/return`, `/eximbay/status` | 엑심베이 인증 결과 수신 |

장애 주입 항목은 다음과 같습니다.

- `pgDelayMs`: PG 호출마다 이만큼 늦게 응답
- `pgTimeoutAfterCapture`: PG는 캡처했는데 응답을 못 받은 것처럼 예외
- `dbFailureAfterCapture`: 캡처 뒤 DB 반영 단계에서 예외를 던져 롤백
- `pgDecline`: PG가 결제를 거절
- `paypalMockResponse`: PayPal 캡처 요청에 `PayPal-Mock-Response` 헤더로 이 오류 코드를 붙입니다(예: `INSTRUMENT_DECLINED`, `INTERNAL_SERVER_ERROR`). 위 네 가지는 우리 코드가 장애를 흉내 내지만, 이 항목은 PayPal 샌드박스가 실제 오류 응답을 보냅니다. PayPal은 결제를 처리하지 않습니다. 비우거나 빼면 꺼집니다.

## 설정에서 눈여겨볼 것

- `spring.jpa.open-in-view=false`: 켜 두면 요청이 끝날 때까지 커넥션을 쥐고 있어서 구조 B도 PG를 기다리는 동안 커넥션을 반납하지 못합니다.
- `DB_POOL_SIZE`, `DB_CONNECTION_TIMEOUT_MS`: 풀 고갈을 손으로 재현할 때 줄여서 씁니다.
- `RECONCILE_SCHEDULE_ENABLED=true`: 대사를 주기적으로 돌립니다.

## 다루지 않은 것

- outbox 이벤트 발행기는 없습니다. 테이블에 쌓이는 것까지만 봅니다.
- 승인 후 캡처하지 않고 방치된 주문의 만료 처리는 없습니다.
- 대사가 실패 처리한 뒤에 PG 캡처가 늦게 완료되면 웹훅이나 진단 조회로 잡아 환불해야 합니다. 주기 대사는 결제 중인 주문만 봅니다.
