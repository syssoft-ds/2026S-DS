package bankhaus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Thread-safe collector: one row per completed snapshot (Aufgabe 3). */
public class Stats {

    public record Row(String mode, int n, int round, int controlMsgs, int channelMsgs,
                      long capturedSum, long expectedSum, boolean consistent) {}

    private final List<Row> rows = Collections.synchronizedList(new ArrayList<>());

    public void record(String mode, int n, int round, int controlMsgs, int channelMsgs,
                       long capturedSum, long expectedSum, boolean consistent) {
        rows.add(new Row(mode, n, round, controlMsgs, channelMsgs,
                capturedSum, expectedSum, consistent));
    }

    public List<Row> rows() {
        synchronized (rows) {
            return List.copyOf(rows);
        }
    }

    public List<Row> rows(String mode) {
        return rows().stream().filter(r -> r.mode().equals(mode)).toList();
    }

    public void writeCsv(Path path) throws IOException {
        StringBuilder sb = new StringBuilder(
                "mode,n,round,control_msgs,channel_msgs,captured_sum,expected_sum,consistent\n");
        for (Row r : rows()) {
            sb.append(r.mode()).append(',').append(r.n()).append(',').append(r.round()).append(',')
              .append(r.controlMsgs()).append(',').append(r.channelMsgs()).append(',')
              .append(r.capturedSum()).append(',').append(r.expectedSum()).append(',')
              .append(r.consistent()).append('\n');
        }
        Files.writeString(path, sb.toString());
    }
}
