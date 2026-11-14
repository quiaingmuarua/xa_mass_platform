package com.xa.mass.integration.androidworkerproof;

import static org.junit.jupiter.api.Assertions.assertThrows;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AndroidWorkerCooldownTest {
    @Test void completeExportRequiresExactIdentitiesAndIndependentDigestAnswers() {
        String one = row("cooldown-one", "a", "0cc175b9c0f1b6a831c399e269772661");
        String two = row("cooldown-two", "abc", "900150983cd24fb0d6963f7d28e17f72");
        String three = row("cooldown-three", "中文", "a7bac2239fcdcb3a067903d8077c4a07");
        AndroidWorkerCooldownMain.verifyExport(two + three + one);
        for (String invalid : List.of(one + two, one + two + three + one,
                one + two + row("other", "中文", "a7bac2239fcdcb3a067903d8077c4a07"),
                one + two + row("cooldown-three", "中文", "00000000000000000000000000000000"),
                one + two + row("cooldown-three", "other", "a7bac2239fcdcb3a067903d8077c4a07"))) {
            assertThrows(ProofFailure.class, () -> AndroidWorkerCooldownMain.verifyExport(invalid));
        }
    }

    private static String row(String id, String input, String digest) {
        return Jsons.toJson(Map.of("messageId", id, "opaqueResultPayload",
                Jsons.toJson(Map.of("algorithm", "MD5", "input", input, "digest", digest)))) + "\n";
    }
}
