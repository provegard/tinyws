package com.programmaticallyspeaking.tinyws;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Comparator.reverseOrder;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertTrue;

public class AutobahnTest extends ClientTestBase {

    private File wstestJsonFile;
    private String reportDir;

    private static final List<String> AllCases = singletonList("*");
    private static final List<String> casesToRun = AllCases;
    private static final List<String> okStatuses = asList("pass( [0-9]+ ms)?", "non-strict", "info");
    private static final List<String> ignoredStatuses = asList("missing", "unimplemented");

    @Override
    protected Server.WebSocketHandler createHandler() {
        return new EchoHandler();
    }

    @BeforeClass
    public void setup() throws IOException, InterruptedException {
        wstestJsonFile = File.createTempFile("wstest", ".json");
        reportDir = wstestJsonFile.getAbsolutePath().replace(".json", "");
        writeWstestJson(wstestJsonFile, reportDir, casesToRun);
    }

    @AfterClass
    public void cleanup() {
        try {
            Files.delete(wstestJsonFile.toPath());
            Path reportPath = FileSystems.getDefault().getPath(reportDir);
            if (Files.exists(reportPath)) {
                Files.walk(reportPath, FileVisitOption.FOLLOW_LINKS)
                        .sorted(reverseOrder())
                        .map(Path::toFile)
                        .peek(System.out::println)
                        .forEach(File::delete);
            }
        } catch (IOException ex) {
            ex.printStackTrace(System.err);
        }
    }

    @DataProvider()
    public Iterator<Object[]> autobahnTestCases() throws IOException, InterruptedException {
        // Quoted path doesn't work on Travis-CI, so only quote if the path contains whitespace.
        Process wstest = Runtime.getRuntime().exec("wstest -s " + quoteIfContainsWhitespace(wstestJsonFile.getAbsolutePath()) + " -m fuzzingclient");

        new StreamReadingThread(wstest.getInputStream(), System.out::println).start();
        new StreamReadingThread(wstest.getErrorStream(), System.err::println).start();

        int exitCode = wstest.waitFor();

        if (exitCode != 0) {
            String err = "wstest returned non-zero exit code: " + exitCode;
            return singletonList(new Object[] { null, err, null }).iterator();
        }

        try {
            Path reportPath = FileSystems.getDefault().getPath(reportDir);
            Path indexFile = reportPath.resolve("index.html");
            Document doc = Jsoup.parse(indexFile.toFile(), "utf-8");
            return doc.select(".agent_case_result_row").stream()
                    .map(elem -> rightPadWithNulls(elem.select("td").stream().map(Element::text).toArray(), 3))
                    .iterator();
        } catch (Exception ex) {
            return singletonList(new Object[] { null, ex.getMessage(), null }).iterator();
        }
    }

    private String quoteIfContainsWhitespace(String path) {
        if (path.indexOf(' ') >= 0) return "\"" + path + "\"";
        return path;
    }

    @Test(dataProvider = "autobahnTestCases")
    public void testAutobahn(String caseNo, String status, String close) {
        if (caseNo == null) throw new IllegalStateException(status);
        String statusLower = status.toLowerCase();
        if (ignoredStatuses.contains(statusLower)) {
            throw new SkipException(status);
        } else {
            List<String> matches = okStatuses.stream().filter(statusLower::matches).collect(toList());
            assertThat(matches).isNotEmpty();
            boolean isNumericCode = close.matches("^[0-9]+$");
            assertTrue(isNumericCode, "Close code: " + close);
        }
    }

    private void writeWstestJson(File filePath, String reportDir, List<String> cases) throws IOException {
        String sb = "{" +
                "\"outdir\": \"" + escape(reportDir) + "\"," +
                "\"servers\": [{ \"url\": \"ws://" + host + ":" + port + "\" }]," +
                "\"cases\":[" + String.join(",", quoted(cases)) + "]" +
                "}";
        Files.write(filePath.toPath(), singletonList(sb));
    }

    private static List<String> quoted(List<String> items) {
        return items.stream().map(s -> "\"" + s + "\"").collect(toList());
    }

    private static String escape(String path) {
        // for Windows
        return path.replace("\\", "\\\\");
    }

    private static Object[] rightPadWithNulls(Object[] array, int desiredLength) {
        if (array.length >= desiredLength) return array;
        Object[] ret = new Object[desiredLength];
        System.arraycopy(array, 0, ret, 0, array.length);
        return ret;
    }
}
