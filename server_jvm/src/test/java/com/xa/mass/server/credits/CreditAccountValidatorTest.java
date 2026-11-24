package com.xa.mass.server.credits;

import static com.xa.mass.server.credits.CreditModels.MAX_POINTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreditAccountValidatorTest {
    private static final String FP = "a".repeat(64);
    private static final String REQ = UUID.randomUUID().toString();

    @Test
    void parsesCanonicalSafeIntegersAndRejectsMalformedValues() {
        assertThat(CreditAccountValidator.parseNonNegativeSafeInt("0")).isZero();
        assertThat(CreditAccountValidator.parseNonNegativeSafeInt(Long.toString(MAX_POINTS))).isEqualTo(MAX_POINTS);
        for (String bad : List.of("", "-1", "01", "1.0", "1.5", "1e5", Long.toString(MAX_POINTS + 1), "abc")) {
            assertThatThrownBy(() -> CreditAccountValidator.parseNonNegativeSafeInt(bad))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> CreditAccountValidator.parseNonNegativeSafeInt(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validatesTransactionSchemaArithmeticAndBusinessAttribution() {
        String valid = "{\"sequence\":1,\"balanceBefore\":10,\"balanceAfter\":15,\"requestId\":\"" + REQ
                + "\",\"userId\":\"1001\",\"actorUserId\":\"1000\",\"delta\":5,\"reason\":\"top-up\",\"createdAtMillis\":100}";
        var tx = CreditAccountValidator.decodeTransaction(valid, "1001", 1);
        assertThat(tx.balanceAfter()).isEqualTo(15);
        assertThat(tx.business()).isNull();

        String validBiz = "{\"sequence\":2,\"balanceBefore\":20,\"balanceAfter\":5,\"requestId\":\"" + REQ
                + "\",\"userId\":\"1001\",\"actorUserId\":\"1000\",\"delta\":-15,\"reason\":\"app-checks task start\","
                + "\"createdAtMillis\":200,\"business\":{\"source\":\"app-checks\",\"taskId\":\"t1\",\"checkId\":\"c1\",\"unitPrice\":3,\"quantity\":5}}";
        var bizTx = CreditAccountValidator.decodeTransaction(validBiz, "1001", 2);
        assertThat(bizTx.business().amount()).isEqualTo(15);

        for (String broken : List.of(
                "{}",
                valid.replace("\"delta\":5", "\"delta\":0"),
                valid.replace("\"balanceAfter\":15", "\"balanceAfter\":16"),
                valid.replace("\"userId\":\"1001\"", "\"userId\":\"1002\""),
                valid.replace("\"actorUserId\":\"1000\"", "\"actorUserId\":\"0000\""),
                valid.replace("\"reason\":\"top-up\"", "\"reason\":\"\""),
                valid.substring(0, valid.length() - 1) + ",\"extra\":1}",
                validBiz.replace("\"quantity\":5", "\"quantity\":4"),
                validBiz.replace("\"source\":\"app-checks\"", "\"source\":\"BAD\""))) {
            assertThatThrownBy(() -> CreditAccountValidator.decodeTransaction(broken, "1001", broken.contains("sequence\":2") ? 2 : 1))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void validatesAppliedAndRejectedReceipts() {
        var applied = CreditAccountValidator.decodeReceipt(
                "{\"fingerprint\":\"" + FP + "\",\"status\":\"applied\",\"sequence\":2}", 5);
        assertThat(applied.isApplied()).isTrue();
        assertThat(applied.sequence()).isEqualTo(2);

        var rejected = CreditAccountValidator.decodeReceipt(
                "{\"fingerprint\":\"" + FP + "\",\"status\":\"rejected\",\"rejectionCode\":\"insufficient_balance\",\"balance\":0}", 0);
        assertThat(rejected.isApplied()).isFalse();
        assertThat(rejected.rejectionCode()).isEqualTo("insufficient_balance");

        for (String broken : List.of(
                "{\"fingerprint\":\"short\",\"status\":\"applied\",\"sequence\":1}",
                "{\"fingerprint\":\"" + FP + "\",\"status\":\"applied\",\"sequence\":6}",
                "{\"fingerprint\":\"" + FP + "\",\"status\":\"applied\",\"sequence\":1,\"extra\":true}",
                "{\"fingerprint\":\"" + FP + "\",\"status\":\"rejected\",\"rejectionCode\":\"other\",\"balance\":0}",
                "{\"fingerprint\":\"" + FP + "\",\"status\":\"unknown\"}")) {
            assertThatThrownBy(() -> CreditAccountValidator.decodeReceipt(broken, 5))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
