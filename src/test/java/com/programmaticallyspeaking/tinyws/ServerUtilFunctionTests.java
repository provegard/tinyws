package com.programmaticallyspeaking.tinyws;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

import static org.testng.Assert.assertEquals;
import static org.testng.internal.junit.ArrayAsserts.assertArrayEquals;
import static org.assertj.core.api.Assertions.assertThat;

public class ServerUtilFunctionTests {

    @DataProvider
    public Object[][] numberToBytes_data() {
        return new Object[][] {
            { 255, 1, new byte[] { (byte) 255 } },
            { 0, 2, new byte[] { 0, 0 } },
            { 99, 2, new byte[] { 0, 99 } },
            { Integer.MAX_VALUE, 4, new byte[] { 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff } },
            { 65536, 8, new byte[] { 0, 0, 0, 0, 0, 1, 0, 0 } }
        };
    }

    @Test(dataProvider = "numberToBytes_data")
    public void numberToBytes_should_yield_correct_data(int number, int len, byte[] expected) throws IOException {
        byte[] actual = Server.numberToBytes(number, len, null);
        assertArrayEquals(expected, actual);
    }

    @DataProvider
    public Object[][] createResponseKey_data() {
        return new Object[][] {
            { "dGhlIHNhbXBsZSBub25jZQ==", "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=" }
        };
    }

    @Test(dataProvider = "createResponseKey_data")
    public void createResponseKey_should_create_correct_key(String input, String expected) throws NoSuchAlgorithmException {
        String actual = Server.createResponseKey(input);
        assertEquals(actual, expected);
    }

    @DataProvider
    public Object[][] unmaskIfNeededInPlace_data() {
        return new Object[][] {
            {"no mask", new byte[] {1, 2, 3, 4}, null, new byte[] {1, 2, 3, 4}},
            {"mask", new byte[] {1, 2, 3, 4}, new byte[] {3, 3, 3, 3}, new byte[] {2, 1, 0, 7}},
            {"mask, data len 1", new byte[] {1}, new byte[] {3, 3, 3, 3}, new byte[] {2}},
            {"mask, data len 2", new byte[] {1, 2}, new byte[] {3, 3, 3, 3}, new byte[] {2, 1}},
            {"mask, data len 3", new byte[] {1, 2, 3}, new byte[] {3, 3, 3, 3}, new byte[] {2, 1, 0}}
        };
    }

    @Test(dataProvider = "unmaskIfNeededInPlace_data")
    public void unmaskIfNeededInPlace_should_apply_mask(String desc, byte[] data, byte[] mask, byte[] expected) {
        Server.unmaskIfNeededInPlace(data, mask);
        assertThat(data).isEqualTo(expected);
    }

    @Test(enabled = false)
    public void unmaskIfNeededInPlace_perf() {
        byte[] data = new byte[16 * 1024 * 1024]; // 16 MB
        Random r = new Random();
        r.nextBytes(data);
        byte[] mask = new byte[] {1, 2, 3, 4};
        long before = System.nanoTime();
        int count = 100;
        for (int i = 0; i < count; i++) {
            Server.unmaskIfNeededInPlace(data, mask);
        }
        long after = System.nanoTime();
        double elapsedMs = (after - before) / 1000000d;
        double bytesPerMs = (data.length * count) / elapsedMs;
        System.out.println("bytes / ms = " + bytesPerMs);
    }
}
