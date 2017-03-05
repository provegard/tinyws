package com.programmaticallyspeaking.tinyws;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import static org.testng.Assert.assertEquals;
import static org.testng.internal.junit.ArrayAsserts.assertArrayEquals;

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
        byte[] actual = Server.numberToBytes(number, len);
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
}
