package ru.corelia;

import static org.junit.jupiter.api.Assertions.*;
import static ru.corelia.support.Json.*;
import org.junit.jupiter.api.Test;
import ru.corelia.document.KidOps;
import ru.corelia.http.ApiException;

class KidOpsValidationTest {
    @Test
    void validatesLengthsFormatsAndOptionalMiddleName() {
        var valid = object("contractNumber", "ОПС-123-4567-1234567", "contractDate", "2024-02-29",
                "signingYear", 2024, "lastName", "Я".repeat(40), "firstName", "И".repeat(255),
                "middleName", "О".repeat(256), "snils", "123-456-789 00");
        assertEquals(2024, number(KidOps.validate(valid, false), "signingYear", 0));
        for (String field : java.util.List.of("lastName", "firstName", "middleName")) {
            var invalid = copy(valid); invalid.put(field, text(valid, field) + "Х");
            assertThrows(ApiException.class, () -> KidOps.validate(invalid, false));
        }
        for (String field : java.util.List.of("signingYear", "lastName", "firstName", "snils", "contractDate", "contractNumber")) {
            var invalid = copy(valid); invalid.putNull(field);
            assertThrows(ApiException.class, () -> KidOps.validate(invalid, true));
        }
        for (var year : java.util.List.of(object("signingYear", "2024"), object("signingYear", 2024.5), object("signingYear", 10000), object("signingYear", 999)))
            assertThrows(ApiException.class, () -> KidOps.validate(year, true));
        assertThrows(ApiException.class, () -> KidOps.validate(object("contractNumber", "ОПС-ABC-4567-1234567"), true));
        assertThrows(ApiException.class, () -> KidOps.validate(object("snils", "12345678900"), true));
        assertThrows(ApiException.class, () -> KidOps.validate(object("status", "STORED"), true));
        assertEquals("", text(KidOps.validate(object("middleName", null), true), "middleName"));
        valid.remove("middleName");
        assertEquals("", text(KidOps.validate(valid, false), "middleName"));
    }
}
