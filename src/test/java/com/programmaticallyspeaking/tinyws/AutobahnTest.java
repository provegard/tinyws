package com.programmaticallyspeaking.tinyws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.StreamSupport;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Comparator.reverseOrder;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;

public class AutobahnTest extends ClientTestBase {

    private File wstestJsonFile;
    private String reportDir;

    private static final List<String> AllCases = singletonList("*");
    private static final List<String> casesToRun = AllCases;
    private static final List<String> ignoredBehaviors = asList("missing", "unimplemented");
    private static final List<String> okBehaviors = asList("ok", "non-strict", "informational");
    private static final List<String> okCloseBehaviors = asList("ok", "informational");

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
            Path jsonReport = reportPath.resolve("index.json");

            ObjectMapper mapper = new ObjectMapper();
            try(InputStreamReader reader = new FileReader(jsonReport.toFile())) {
                JsonNode report = mapper.readTree(reader);
                JsonNode serverNode = report.at("/TinyWS Server @VERSION@");
                return StreamSupport.stream(Spliterators.spliteratorUnknownSize(serverNode.fieldNames(), Spliterator.ORDERED), false)
                        .map(fieldName-> new Object[]{
                                fieldName,
                                serverNode.at("/" + fieldName + "/behavior").asText(),
                                serverNode.at("/" + fieldName + "/behaviorClose").asText()
                        }).iterator();
            }
        } catch (Exception ex) {
            return singletonList(new Object[] { null, ex.getMessage(), null }).iterator();
        }
    }

    private String quoteIfContainsWhitespace(String path) {
        if (path.indexOf(' ') >= 0) return "\"" + path + "\"";
        return path;
    }

    @Test(dataProvider = "autobahnTestCases")
    public void testAutobahn(String caseNo, String behavior, String behaviorClose) {
        if (caseNo == null) throw new IllegalStateException(behavior);
        String behaviorLower = behavior.toLowerCase();
        String behaviorCloseLower = behaviorClose.toLowerCase();
        if (ignoredBehaviors.contains(behaviorLower)) {
            throw new SkipException(behavior);
        } else {
            List<String> matchingBehaviors = okBehaviors.stream().filter(behaviorLower::matches).collect(toList());
            assertThat(matchingBehaviors).isNotEmpty().describedAs("Behavior should be ok-ish: " + behavior);

            List<String> matchingCloseBehaviors = okCloseBehaviors.stream().filter(behaviorCloseLower::matches).collect(toList());
            assertThat(matchingCloseBehaviors).isNotEmpty().describedAs("Close behavior should be ok-ish: " + behaviorClose);
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
}
