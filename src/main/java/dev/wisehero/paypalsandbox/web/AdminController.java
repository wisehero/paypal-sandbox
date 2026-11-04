package dev.wisehero.paypalsandbox.web;

import dev.wisehero.paypalsandbox.checkout.MismatchFinder;
import dev.wisehero.paypalsandbox.checkout.PaymentReconciler;
import dev.wisehero.paypalsandbox.product.ProductRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class AdminController {

    private final ProductRepository products;
    private final PaymentReconciler reconciler;
    private final MismatchFinder mismatchFinder;

    /** 결제와 상관없는 조회. 커넥션 풀이 고갈되면 이 API도 같이 멈춘다. */
    @GetMapping("/products")
    List<ProductResponse> products() {
        return products.findAll().stream().map(ProductResponse::from).toList();
    }

    @PostMapping("/reconcile")
    List<PaymentReconciler.Result> reconcileStale() {
        return reconciler.reconcileStale();
    }

    /** 시간 조건 없이 주문 하나를 바로 대사한다. 실험할 때 기다리지 않으려고 둔 API다. */
    @PostMapping("/reconcile/{orderId}")
    PaymentReconciler.Result reconcile(@PathVariable Long orderId) {
        return reconciler.reconcile(orderId, true);
    }

    @GetMapping("/diagnostics/mismatches")
    List<MismatchFinder.Mismatch> mismatches() {
        return mismatchFinder.find();
    }
}
