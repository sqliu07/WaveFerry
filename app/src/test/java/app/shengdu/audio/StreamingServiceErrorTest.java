package app.shengdu.audio;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StreamingServiceErrorTest {
    @Test
    public void readableErrorStopsAtACyclicCauseChain() {
        CyclicThrowable first = new CyclicThrowable("first");
        CyclicThrowable second = new CyclicThrowable("second");
        first.cause = second;
        second.cause = first;

        String message = StreamingService.readableError(first);

        assertTrue(message.equals("投送失败：first") || message.equals("投送失败：second"));
    }

    private static final class CyclicThrowable extends RuntimeException {
        private Throwable cause;

        private CyclicThrowable(String message) {
            super(message, null);
        }

        @Override
        public synchronized Throwable getCause() {
            return cause;
        }
    }
}
