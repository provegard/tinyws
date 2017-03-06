package com.programmaticallyspeaking.tinyws;

import com.programmaticallyspeaking.tinyws.Server.Frame;
import com.programmaticallyspeaking.tinyws.Server.FrameWriter;
import com.programmaticallyspeaking.tinyws.Server.PayloadCoder;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import static org.testng.internal.junit.ArrayAsserts.assertArrayEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static java.util.Arrays.asList;
import static org.testng.Assert.*;

public abstract class FrameTests {

    static ByteArrayInputStream write(ThrowingConsumer<FrameWriter> writerFun, int maxFrameSize) throws Throwable {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PayloadCoder coder = new PayloadCoder();
        FrameWriter writer = new FrameWriter(out, coder, maxFrameSize);
        writerFun.accept(writer);

        return new ByteArrayInputStream(out.toByteArray());
    }

    public static class A_single_written_text_frame {
        private Frame frame;

        @BeforeClass
        public void Write_and_read() throws Throwable {
            ByteArrayInputStream in = write(w -> w.writeText("hello åäö"), 0);
            this.frame = Frame.read(in);
        }

        @Test
        public void is_final() {
            assertTrue(frame.isFin);
        }

        @Test
        public void is_not_control() {
            assertFalse(frame.isControl());
        }

        @Test
        public void has_correct_opcode() {
            assertEquals(frame.opCode, 1);
        }

        @Test
        public void has_correct_data() {
            String text = new String(frame.payloadData, StandardCharsets.UTF_8);
            assertEquals(text, "hello åäö");
        }
    }

    public static class A_single_written_binary_frame {
        private Frame frame;

        @BeforeClass
        public void Write_and_read() throws Throwable {
            ByteArrayInputStream in = write(w -> w.writeBinary(new byte[] { 1, 2, 3, 4 }), 0);
            this.frame = Frame.read(in);
        }

        @Test
        public void has_correct_opcode() {
            assertEquals(frame.opCode, 2);
        }

        @Test
        public void has_correct_data() {
            assertArrayEquals(new byte[]{1, 2, 3, 4}, frame.payloadData);
        }
    }

    public static class Fragmented_text_frames {
        private Frame frame1;
        private Frame frame2;

        @BeforeClass
        public void Write_and_read() throws Throwable {
            ByteArrayInputStream in = write(w -> w.writeText("hello world there"), 10);
            this.frame1 = Frame.read(in);
            this.frame2 = Frame.read(in);
        }

        @Test
        public void has_correct_opcode_for_frame_1() {
            assertEquals(frame1.opCode, 1);
        }

        @Test
        public void has_zero_opcode_for_frame_2() {
            assertEquals(frame2.opCode, 0);
        }

        @Test
        public void indicates_that_frame_1_is_not_final() {
            assertFalse(frame1.isFin);
        }

        @Test
        public void indicates_that_frame_2_is_final() {
            assertTrue(frame2.isFin);
        }

        @Test
        public void have_correct_data_when_merged() {
            Frame frame = Frame.merge(asList(frame1, frame2));
            String text = new String(frame.payloadData, StandardCharsets.UTF_8);
            assertEquals(text, "hello world there");
        }
    }

    public static class Fragmented_binary_frames {
        private Frame frame1;
        private Frame frame2;

        @BeforeClass
        public void Write_and_read() throws Throwable {
            ByteArrayInputStream in = write(w -> w.writeBinary(new byte[] { 1, 2, 3, 4 }), 2);
            this.frame1 = Frame.read(in);
            this.frame2 = Frame.read(in);
        }

        @Test
        public void have_correct_data_when_merged() {
            Frame frame = Frame.merge(asList(frame1, frame2));
            assertArrayEquals(new byte[]{1, 2, 3, 4}, frame.payloadData);
        }
    }


    @FunctionalInterface
    interface ThrowingConsumer<T> {
        void accept(T t) throws Throwable;
    }
}
