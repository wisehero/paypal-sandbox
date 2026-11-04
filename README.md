# paypal-sandbox

주문 결제 흐름에서 PG 호출을 DB 트랜잭션 안에 넣을 때와 밖으로 뺄 때 무엇이 달라지는지 직접 장애를 넣어 보며 비교하는 실험 프로젝트입니다. PG는 PayPal 샌드박스나 메모리 안의 가짜 PG 중 하나를 고를 수 있습니다.

Spring Boot 4.1, Java 25, H2 메모리 DB를 씁니다.

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

이 결과는 `src/test`의 테스트로 재현됩니다.

```
./gradlew test
```

- `CheckoutScenarioTest`: 장애별로 두 구조를 비교합니다. 멱등 캡처, 실패 주문의 환불(보상)도 확인합니다.
- `ConnectionPoolExhaustionTest`: 커넥션 풀 2개, PG 지연 1.5초에서 결제 2건을 동시에 돌리고 그 사이에 상품 조회가 되는지 봅니다.

## 화면

`./gradlew bootRun` 후 http://localhost:8080 을 엽니다.

- **쇼핑 화면** (`/`): 상품 목록 → 장바구니 → 주문서 → PG 승인 → 결제 완료 화면 순서로 진행합니다. 주문서의 "실험 설정"에서 결제 처리 구조(트랜잭션 안, 분리)를 고릅니다. 결제 완료 화면 아래 "실험 정보"에 우리 주문 상태와 PG 실제 상태를 나란히 보여 줍니다.
- **실험 콘솔** (`/lab.html`): 장애 주입, 주문별 캡처와 대사, 불일치 진단, 동시 결제 실험(커넥션 풀 사용량과 상품 조회 성공 여부를 실시간으로 표시)을 다룹니다.

결제 수단은 PayPal 샌드박스와 테스트 결제(가짜 PG) 중에서 고릅니다. 가짜 PG도 승인 화면(`/fake-pg.html`)을 거쳐서 PayPal과 같은 흐름을 탑니다. PayPal 키가 없으면 테스트 결제만 쓸 수 있습니다.

## 실행

### 가짜 PG로 실행 (기본값)

구매자 승인 단계를 건너뛰므로 주문을 만들면 바로 캡처할 수 있습니다.

```
./gradlew bootRun
```

```bash
# 장애 주입: PG 캡처 뒤 DB 반영 실패
curl -X PUT localhost:8080/faults -H 'Content-Type: application/json' \
  -d '{"pgDelayMs":0,"pgTimeoutAfterCapture":false,"dbFailureAfterCapture":true,"pgDecline":false}'

# 구조 A
curl -X POST localhost:8080/orders -H 'Content-Type: application/json' -d '{"productId":1,"quantity":1}'
curl -X POST 'localhost:8080/orders/1/capture?strategy=in-tx'

# 구조 B
curl -X POST localhost:8080/orders -H 'Content-Type: application/json' -d '{"productId":1,"quantity":1}'
curl -X POST 'localhost:8080/orders/2/capture?strategy=split'

# 장애를 끄고 대사
curl -X DELETE localhost:8080/faults
curl -X POST localhost:8080/reconcile/2

# PG는 결제됐는데 주문은 결제 완료가 아닌 건
curl localhost:8080/diagnostics/mismatches
```

### PayPal 샌드박스로 실행

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
4. 승인하면 `/checkout-return.html`로 돌아와서 고른 구조로 캡처하고 결제 완료 화면으로 넘어갑니다. 장애 주입은 PayPal 연동에서도 똑같이 쓸 수 있습니다.

웹훅까지 받아 보려면 ngrok 같은 터널로 `/webhooks/paypal`을 외부에 열고, PayPal 대시보드에 웹훅을 등록한 뒤 `PAYPAL_WEBHOOK_ID`를 넘깁니다. `PAYMENT.CAPTURE.COMPLETED`가 오면 해당 주문을 대사합니다. 웹훅 내용을 그대로 믿지 않고 PayPal 주문을 다시 조회해서 판단합니다.

## API

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/products` | 상품 목록. 결제와 상관없는 조회 |
| POST | `/checkout` | 장바구니 상품으로 주문 생성 + PG 주문 생성. 결제 처리 구조를 함께 받음 |
| POST | `/checkout/complete` | PG 승인 후 호출. 고른 구조로 캡처 |
| POST | `/orders` | 주문 생성 + PG 주문 생성 (실험 콘솔용) |
| POST | `/orders/{id}/capture?strategy=in-tx\|split` | 캡처 |
| GET | `/orders/{id}` | 주문 조회 |
| POST | `/reconcile` | `reconcile.stale-after`(기본 30초)가 지난 결제 중 주문을 모두 대사 |
| POST | `/reconcile/{id}` | 주문 하나를 바로 대사 |
| GET | `/diagnostics/mismatches` | PG는 캡처됐는데 주문은 결제 완료가 아닌 건 |
| GET, PUT, DELETE | `/faults` | 장애 주입 조회, 설정, 초기화 |
| POST | `/experiments/concurrent-captures` | 같은 상품을 가짜 PG로 동시에 여러 건 캡처 |
| GET | `/experiments/pool` | 커넥션 풀 사용량 |

장애 주입 항목은 다음과 같습니다.

- `pgDelayMs`: PG 호출마다 이만큼 늦게 응답
- `pgTimeoutAfterCapture`: PG는 캡처했는데 응답을 못 받은 것처럼 예외
- `dbFailureAfterCapture`: 캡처 뒤 DB 반영 단계에서 예외를 던져 롤백
- `pgDecline`: PG가 결제를 거절

## 설정에서 눈여겨볼 것

- `spring.jpa.open-in-view=false`: 켜 두면 요청이 끝날 때까지 커넥션을 쥐고 있어서 구조 B도 PG를 기다리는 동안 커넥션을 반납하지 못합니다.
- `DB_POOL_SIZE`, `DB_CONNECTION_TIMEOUT_MS`: 풀 고갈을 손으로 재현할 때 줄여서 씁니다.
- `RECONCILE_SCHEDULE_ENABLED=true`: 대사를 주기적으로 돌립니다.

## 다루지 않은 것

- outbox 이벤트 발행기는 없습니다. 테이블에 쌓이는 것까지만 봅니다.
- 승인 후 캡처하지 않고 방치된 주문의 만료 처리는 없습니다.
- 대사가 실패 처리한 뒤에 PG 캡처가 늦게 완료되면 웹훅이나 진단 조회로 잡아 환불해야 합니다. 주기 대사는 결제 중인 주문만 봅니다.
