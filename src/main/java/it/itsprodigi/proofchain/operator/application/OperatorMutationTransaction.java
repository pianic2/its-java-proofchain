package it.itsprodigi.proofchain.operator.application;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperatorMutationTransaction {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T execute(Supplier<T> mutation) {
        return Objects.requireNonNull(mutation, "mutation must not be null").get();
    }
}
