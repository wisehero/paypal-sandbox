package dev.wisehero.paypalsandbox.fault;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/faults")
@RequiredArgsConstructor
class FaultController {

    private final FaultInjector faults;

    @GetMapping
    FaultInjector.Settings get() {
        return faults.current();
    }

    @PutMapping
    FaultInjector.Settings put(@RequestBody FaultInjector.Settings settings) {
        faults.apply(settings);
        return faults.current();
    }

    @DeleteMapping
    FaultInjector.Settings reset() {
        faults.reset();
        return faults.current();
    }
}
