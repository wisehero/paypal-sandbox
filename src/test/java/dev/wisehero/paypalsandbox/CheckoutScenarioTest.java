package dev.wisehero.paypalsandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.wisehero.paypalsandbox.checkout.CheckoutService;
import dev.wisehero.paypalsandbox.checkout.MismatchFinder;
import dev.wisehero.paypalsandbox.checkout.OrderService;
import dev.wisehero.paypalsandbox.checkout.PaymentReconciler;
import dev.wisehero.paypalsandbox.fault.FaultInjector;
import dev.wisehero.paypalsandbox.fault.SimulatedDbFailureException;
import dev.wisehero.paypalsandbox.order.CaptureStrategy;
import dev.wisehero.paypalsandbox.order.OrderStatus;
import dev.wisehero.paypalsandbox.order.PurchaseOrder;
import dev.wisehero.paypalsandbox.order.PurchaseOrderRepository;
import dev.wisehero.paypalsandbox.payment.OutboxEventRepository;
import dev.wisehero.paypalsandbox.payment.PaymentRecordRepository;
import dev.wisehero.paypalsandbox.pg.FakePaymentGateway;
import dev.wisehero.paypalsandbox.pg.FakePgConfig;
import dev.wisehero.paypalsandbox.pg.PgDeclinedException;
import dev.wisehero.paypalsandbox.pg.PgProvider;
import dev.wisehero.paypalsandbox.pg.PgTimeoutException;
import dev.wisehero.paypalsandbox.product.Product;
import dev.wisehero.paypalsandbox.product.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 장애를 두 구조에 똑같이 주입하고 결과를 비교한다.
 * PG는 메모리 안의 가짜 PG를 쓴다.
 */
@SpringBootTest(properties = "pg.provider=fake")
@Import({FakePgConfig.class, MySqlTestConfig.class})
class CheckoutScenarioTest {

    @Autowired
    OrderService orderService;
    @Autowired
    CheckoutService checkoutService;
    @Autowired
    PaymentReconciler reconciler;
    @Autowired
    MismatchFinder mismatchFinder;
    @Autowired
    FaultInjector faults;
    @Autowired
    FakePaymentGateway fakePg;
    @Autowired
    ProductRepository products;
    @Autowired
    PaymentRecordRepository payments;
    @Autowired
    OutboxEventRepository outbox;
    @Autowired
    PurchaseOrderRepository orders;
    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void resetFaults() {
        faults.reset();
    }

    @Test
    @DisplayName("장애가 없으면 두 구조 모두 PAID, 재고 차감, 결제 기록 1건")
    void happyPath() {
        for (CaptureStrategy strategy : CaptureStrategy.values()) {
            Product product = newProduct(10);
            Long orderId = newOrder(product, 2);

            PurchaseOrder paid = orderService.capture(orderId, strategy);

            assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(stockOf(product)).isEqualTo(8);
            assertThat(payments.findByOrderId(orderId)).hasSize(1);
            assertThat(outbox.findByOrderId(orderId)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("PG 캡처 성공 후 DB 반영 실패")
    class DbFailureAfterCapture {

        @Test
        @DisplayName("트랜잭션 안: 롤백되어 주문은 CREATED. PG에서는 돈이 빠져나갔고 흔적은 진단 전수 조회로만 보인다")
        void inTransaction() {
            Product product = newProduct(10);
            Long orderId = newOrder(product, 1);
            faults.apply(FaultInjector.Settings.NONE.withDbFailureAfterCapture());

            assertThatThrownBy(() -> orderService.capture(orderId, CaptureStrategy.IN_TX))
                    .isInstanceOf(SimulatedDbFailureException.class);

            PurchaseOrder order = orderService.get(orderId);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
            assertThat(order.getCaptureId()).isNull();
            assertThat(payments.findByOrderId(orderId)).isEmpty();
            assertThat(stockOf(product)).isEqualTo(10);
            assertThat(fakePg.getOrder(order.getPgOrderId()).isCaptured()).isTrue();

            // 결제를 시도조차 안 한 주문과 상태가 같아서 대사 대상(결제 중, 결과 모름)에 잡히지 않는다.
            assertThat(reconciler.reconcile(orderId, true).action())
                    .isEqualTo(PaymentReconciler.Action.NOTHING);
            assertThat(mismatchFinder.find())
                    .anySatisfy(m -> assertThat(m.orderId()).isEqualTo(orderId));
        }

        @Test
        @DisplayName("트랜잭션 분리: 주문이 PAYMENT_IN_PROGRESS로 남고 대사가 PAID로 확정한다")
        void split() {
            Product product = newProduct(10);
            Long orderId = newOrder(product, 1);
            faults.apply(FaultInjector.Settings.NONE.withDbFailureAfterCapture());

            assertThatThrownBy(() -> orderService.capture(orderId, CaptureStrategy.SPLIT))
                    .isInstanceOf(SimulatedDbFailureException.class);

            assertThat(orderService.get(orderId).getStatus()).isEqualTo(OrderStatus.PAYMENT_IN_PROGRESS);
            assertThat(stockOf(product)).isEqualTo(9);

            faults.reset();
            assertThat(reconciler.reconcile(orderId, true).action())
                    .isEqualTo(PaymentReconciler.Action.CONFIRMED_PAID);

            assertThat(orderService.get(orderId).getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(payments.findByOrderId(orderId)).hasSize(1);
            assertThat(mismatchFinder.find()).noneSatisfy(m -> assertThat(m.orderId()).isEqualTo(orderId));
        }
    }

    @Nested
    @DisplayName("PG가 결제를 끝냈는데 응답이 타임아웃")
    class TimeoutAfterCapture {

        @Test
        @DisplayName("트랜잭션 안: 롤백되어 CREATED. 결제가 됐는지 모르는 채로 사라진다")
        void inTransaction() {
            Product product = newProduct(10);
            Long orderId = newOrder(product, 1);
            faults.apply(FaultInjector.Settings.NONE.withPgTimeoutAfterCapture());

            assertThatThrownBy(() -> orderService.capture(orderId, CaptureStrategy.IN_TX))
                    .isInstanceOf(PgTimeoutException.class);

            assertThat(orderService.get(orderId).getStatus()).isEqualTo(OrderStatus.CREATED);
            assertThat(mismatchFinder.find()).anySatisfy(m -> assertThat(m.orderId()).isEqualTo(orderId));
        }

        @Test
        @DisplayName("트랜잭션 분리: PAYMENT_UNKNOWN으로 남기고 대사가 PG를 조회해 PAID로 확정한다")
        void split() {
            Product product = newProduct(10);
            Long orderId = newOrder(product, 1);
            faults.apply(FaultInjector.Settings.NONE.withPgTimeoutAfterCapture());

            assertThatThrownBy(() -> orderService.capture(orderId, CaptureStrategy.SPLIT))
                    .isInstanceOf(PgTimeoutException.class);
            assertThat(orderService.get(orderId).getStatus()).isEqualTo(OrderStatus.PAYMENT_UNKNOWN);

            faults.reset();
            assertThat(reconciler.reconcile(orderId, true).action())
                    .isEqualTo(PaymentReconciler.Action.CONFIRMED_PAID);
            assertThat(orderService.get(orderId).getStatus()).isEqualTo(OrderStatus.PAID);
        }
    }

    @Test
    @DisplayName("트랜잭션 분리: PG 거절이면 실패 처리하고 예약한 재고를 돌려준다")
    void splitDecline() {
        Product product = newProduct(10);
        Long orderId = newOrder(product, 3);
        faults.apply(FaultInjector.Settings.NONE.withPgDecline());

        assertThatThrownBy(() -> orderService.capture(orderId, CaptureStrategy.SPLIT))
                .isInstanceOf(PgDeclinedException.class);

        assertThat(orderService.get(orderId).getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(stockOf(product)).isEqualTo(10);
    }

    @Test
    @DisplayName("같은 멱등 키로 캡처를 다시 요청하면 PG는 새로 결제하지 않고 처음 캡처를 돌려준다")
    void idempotentCapture() {
        Product product = newProduct(10);
        Long orderId = newOrder(product, 1);
        faults.apply(FaultInjector.Settings.NONE.withPgTimeoutAfterCapture());
        assertThatThrownBy(() -> orderService.capture(orderId, CaptureStrategy.SPLIT))
                .isInstanceOf(PgTimeoutException.class);
        String pgOrderId = orderService.get(orderId).getPgOrderId();
        String firstCapture = fakePg.getOrder(pgOrderId).captureId();

        faults.reset();
        assertThat(fakePg.capture(pgOrderId, "capture-" + orderId).captureId()).isEqualTo(firstCapture);
    }

    @Test
    @DisplayName("주문은 실패로 끝났는데 PG에서 결제가 됐으면 대사가 환불한다")
    void refundWhenOrderFailedButCaptured() {
        Product product = newProduct(10);
        Long orderId = newOrder(product, 1);
        faults.apply(FaultInjector.Settings.NONE.withPgTimeoutAfterCapture());
        assertThatThrownBy(() -> orderService.capture(orderId, CaptureStrategy.SPLIT))
                .isInstanceOf(PgTimeoutException.class);
        faults.reset();

        // 운영자가 PG 확인 없이 실패 처리해 버린 상황을 흉내 낸다.
        PurchaseOrder order = orderService.get(orderId);
        markFailedDirectly(orderId);

        assertThat(reconciler.reconcile(orderId, true).action())
                .isEqualTo(PaymentReconciler.Action.REFUNDED);
        assertThat(orderService.get(orderId).getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(fakePg.isRefunded(order.getPgOrderId())).isTrue();
    }

    @Test
    @DisplayName("체크아웃: 상품 여러 개를 담아 PG 승인 후 완료하면 고른 구조로 캡처하고 상품마다 재고를 줄인다")
    void checkoutWithMultipleItems() {
        Product a = newProduct(10);
        Product b = newProduct(5);
        OrderService.CreatedOrder created = checkoutService.start(
                List.of(new OrderService.Item(a.getId(), 2), new OrderService.Item(b.getId(), 1)),
                PgProvider.FAKE, CaptureStrategy.SPLIT);

        CheckoutService.CompleteResult result = checkoutService.complete(created.pgOrderId());

        assertThat(result.status()).isEqualTo(OrderStatus.PAID);
        assertThat(result.error()).isNull();
        assertThat(orderService.get(created.orderId()).getAmount()).isEqualByComparingTo("30.00");
        assertThat(stockOf(a)).isEqualTo(8);
        assertThat(stockOf(b)).isEqualTo(4);

        // 같은 화면을 다시 열어도 두 번 캡처하지 않는다.
        assertThat(checkoutService.complete(created.pgOrderId()).status()).isEqualTo(OrderStatus.PAID);
        assertThat(payments.findByOrderId(created.orderId())).hasSize(1);
    }

    @Test
    @DisplayName("체크아웃: 트랜잭션 안 구조에서 캡처 뒤 DB 실패가 나면 고객은 실패를 보고 재고는 그대로, PG는 결제 완료")
    void checkoutInTransactionDbFailure() {
        Product a = newProduct(10);
        OrderService.CreatedOrder created = checkoutService.start(
                List.of(new OrderService.Item(a.getId(), 1)), PgProvider.FAKE, CaptureStrategy.IN_TX);
        faults.apply(FaultInjector.Settings.NONE.withDbFailureAfterCapture());

        CheckoutService.CompleteResult result = checkoutService.complete(created.pgOrderId());

        assertThat(result.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(result.error()).contains("DB 반영 실패");
        assertThat(stockOf(a)).isEqualTo(10);
        assertThat(fakePg.getOrder(created.pgOrderId()).isCaptured()).isTrue();
    }

    private void markFailedDirectly(Long orderId) {
        tx.executeWithoutResult(s -> orders.findByIdForUpdate(orderId).orElseThrow().markFailed("수동 실패 처리"));
    }

    private Product newProduct(int stock) {
        return products.save(new Product("test", new BigDecimal("10.00"), stock));
    }

    private Long newOrder(Product product, int quantity) {
        return orderService.create(product.getId(), quantity).orderId();
    }

    private int stockOf(Product product) {
        return products.findById(product.getId()).orElseThrow().getStock();
    }
}
