package vangogh.net;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for ApiResponse — previously completely untested. */
class ApiResponseTest {

    @Test
    void isSuccessTrueFor200() {
        assertTrue(new ApiResponse(200, "{}", null).isSuccess());
    }

    @Test
    void isSuccessTrueFor299() {
        assertTrue(new ApiResponse(299, "{}", null).isSuccess());
    }

    @Test
    void isSuccessFalseFor199() {
        assertFalse(new ApiResponse(199, "", null).isSuccess());
    }

    @Test
    void isSuccessFalseFor300() {
        assertFalse(new ApiResponse(300, "", null).isSuccess());
    }

    @Test
    void isSuccessFalseFor500() {
        assertFalse(new ApiResponse(500, "", null).isSuccess());
    }

    @Test
    void fieldsAreExposed() {
        ApiResponse r = new ApiResponse(201, "body", "err");
        assertEquals(201, r.statusCode);
        assertEquals("body", r.body);
        assertEquals("err", r.error);
    }
}
