package com.tesults.junit5;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TesultsListenerTest {
    private static final List<String> TESULTS_PROPERTIES = Arrays.asList(
            "tesultsConfig",
            "tesultsTarget",
            "tesultsOutputFile",
            "tesultsFiles",
            "tesultsNoSuites",
            "tesultsBuildName",
            "tesultsBuildDesc",
            "tesultsBuildResult",
            "tesultsBuildReason"
    );

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void resetState() {
        for (String property : TESULTS_PROPERTIES) {
            System.clearProperty(property);
        }
        TesultsListener.files.clear();
    }

    @Test
    void neitherTargetNorOutputPreservesDisabledBehavior() {
        CapturingListener listener = new CapturingListener();

        execute(listener, PassingFixture.class);

        assertTrue(listener.disabled);
        assertTrue(listener.uploads.isEmpty());
        assertTrue(listener.cases.isEmpty());
    }

    @Test
    void targetOnlyPreservesUploadAndAddsMatchingMetadata() {
        System.setProperty("tesultsTarget", "target-token");
        CapturingListener listener = new CapturingListener();

        execute(listener, PassingAndFailingFixture.class);

        assertEquals(1, listener.uploads.size());
        Map<String, Object> upload = listener.uploads.get(0);
        assertEquals("target-token", upload.get("target"));
        assertMetadata(upload);
        assertCaseResults((List<Map<String, Object>>) ((Map<String, Object>) upload.get("results")).get("cases"));
    }

    @Test
    void outputOnlyWritesResultsWithoutUploading() throws IOException {
        Path output = temporaryDirectory.resolve("nested/results.json");
        System.setProperty("tesultsOutputFile", output.toString());
        CapturingListener listener = new CapturingListener();

        execute(listener, PassingAndFailingFixture.class);

        assertTrue(listener.uploads.isEmpty());
        JSONObject payload = readJson(output);
        assertEquals("", payload.getString("target"));
        assertMetadata(payload);
        assertCaseResults(payload.getJSONObject("results").getJSONArray("cases"));
    }

    @Test
    void environmentOutputTakesPrecedenceOverSystemProperty() throws IOException {
        Path configuredOutput = temporaryDirectory.resolve("configured.json");
        Path environmentOutput = temporaryDirectory.resolve("environment.json");
        System.setProperty("tesultsOutputFile", configuredOutput.toString());
        CapturingListener listener = new CapturingListener(environmentOutput.toString());

        execute(listener, PassingFixture.class);

        assertTrue(Files.exists(environmentOutput));
        assertFalse(Files.exists(configuredOutput));
    }

    @Test
    void targetAndOutputBothComplete() throws IOException {
        Path output = temporaryDirectory.resolve("results.json");
        System.setProperty("tesultsTarget", "combined-target");
        System.setProperty("tesultsOutputFile", output.toString());
        CapturingListener listener = new CapturingListener();

        execute(listener, PassingFixture.class);

        assertTrue(Files.exists(output));
        assertEquals(1, listener.uploads.size());
        assertEquals("combined-target", listener.uploads.get(0).get("target"));
        assertEquals("", readJson(output).getString("target"));
    }

    @Test
    void outputFailureDoesNotPreventUpload() throws IOException {
        Path directoryInsteadOfFile = Files.createDirectory(temporaryDirectory.resolve("output-directory"));
        System.setProperty("tesultsTarget", "upload-after-write-error");
        System.setProperty("tesultsOutputFile", directoryInsteadOfFile.toString());
        CapturingListener listener = new CapturingListener();

        execute(listener, PassingFixture.class);

        assertEquals(1, listener.uploads.size());
    }

    @Test
    void repeatedTestPlansMergeIntoOneOutputFile() throws IOException {
        Path output = temporaryDirectory.resolve("merged.json");
        System.setProperty("tesultsOutputFile", output.toString());

        execute(new CapturingListener(), FirstForkFixture.class);
        execute(new CapturingListener(), SecondForkFixture.class);

        JSONArray cases = readJson(output).getJSONObject("results").getJSONArray("cases");
        assertEquals(2, cases.length());
    }

    @Test
    void configurationFileTargetAndBuildBehaviorArePreserved() throws IOException {
        Path config = temporaryDirectory.resolve("tesults.properties");
        Files.write(config, Arrays.asList(
                "test-target=resolved-target-token",
                "tesultsBuildName=build-42",
                "tesultsBuildResult=pass"
        ), StandardCharsets.UTF_8);
        System.setProperty("tesultsConfig", config.toString());
        System.setProperty("tesultsTarget", "test-target");
        CapturingListener listener = new CapturingListener();

        execute(listener, PassingFixture.class);

        Map<String, Object> upload = listener.uploads.get(0);
        assertEquals("resolved-target-token", upload.get("target"));
        List<Map<String, Object>> cases = (List<Map<String, Object>>) ((Map<String, Object>) upload.get("results")).get("cases");
        assertEquals(2, cases.size());
        assertEquals("[build]", cases.get(1).get("suite"));
        assertEquals("build-42", cases.get(1).get("name"));
        assertEquals("pass", cases.get(1).get("result"));
    }

    @Test
    void existingFileAttachmentBehaviorIsPreservedInLocalOutput() throws IOException {
        Path filesDirectory = temporaryDirectory.resolve("files");
        Path attachment = filesDirectory.resolve("TesultsListenerTest$PassingFixture/passes/evidence.txt");
        Files.createDirectories(attachment.getParent());
        Files.write(attachment, Collections.singletonList("evidence"), StandardCharsets.UTF_8);
        Path output = temporaryDirectory.resolve("attachments.json");
        System.setProperty("tesultsFiles", filesDirectory.toString());
        System.setProperty("tesultsOutputFile", output.toString());

        execute(new CapturingListener(), PassingFixture.class);

        JSONObject testCase = readJson(output).getJSONObject("results").getJSONArray("cases").getJSONObject(0);
        assertEquals(attachment.toAbsolutePath().toString(), testCase.getJSONArray("files").getString(0));
    }

    @Test
    void serviceLoaderAutomaticallyRegistersThePublishedListener() throws IOException {
        Path output = temporaryDirectory.resolve("automatic-listener.json");
        System.setProperty("tesultsOutputFile", output.toString());
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(PassingFixture.class))
                .build();

        LauncherFactory.create().execute(request);

        assertTrue(Files.exists(output));
        assertEquals("pass", readJson(output)
                .getJSONObject("results")
                .getJSONArray("cases")
                .getJSONObject(0)
                .getString("result"));
    }

    private static void execute(TesultsListener listener, Class<?> testClass) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(testClass))
                .build();
        LauncherConfig config = LauncherConfig.builder()
                .enableTestExecutionListenerAutoRegistration(false)
                .addTestExecutionListeners(listener)
                .build();
        Launcher launcher = LauncherFactory.create(config);
        launcher.execute(request);
    }

    private static JSONObject readJson(Path path) throws IOException {
        return new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }

    private static void assertMetadata(Map<String, Object> payload) {
        Map<String, Object> metadata = (Map<String, Object>) payload.get("metadata");
        assertEquals("tesults-junit5", metadata.get("integration_name"));
        assertEquals("1.3.0", metadata.get("integration_version"));
        assertEquals("junit5", metadata.get("test_framework"));
    }

    private static void assertMetadata(JSONObject payload) {
        JSONObject metadata = payload.getJSONObject("metadata");
        assertEquals("tesults-junit5", metadata.getString("integration_name"));
        assertEquals("1.3.0", metadata.getString("integration_version"));
        assertEquals("junit5", metadata.getString("test_framework"));
    }

    private static void assertCaseResults(List<Map<String, Object>> cases) {
        Map<String, String> results = new HashMap<String, String>();
        for (Map<String, Object> testCase : cases) {
            results.put((String) testCase.get("name"), (String) testCase.get("result"));
        }
        assertEquals("pass", results.get("passes"));
        assertEquals("fail", results.get("fails"));
    }

    private static void assertCaseResults(JSONArray cases) {
        Map<String, String> results = new HashMap<String, String>();
        for (int index = 0; index < cases.length(); index++) {
            JSONObject testCase = cases.getJSONObject(index);
            results.put(testCase.getString("name"), testCase.getString("result"));
        }
        assertEquals("pass", results.get("passes"));
        assertEquals("fail", results.get("fails"));
    }

    static class CapturingListener extends TesultsListener {
        final List<Map<String, Object>> uploads = new ArrayList<Map<String, Object>>();

        CapturingListener() {
            super();
        }

        CapturingListener(String environmentOutput) {
            super();
            outputFile = environmentOutput;
        }

        @Override
        Map<String, Object> upload(Map<String, Object> data) {
            uploads.add(data);
            Map<String, Object> response = new HashMap<String, Object>();
            response.put("success", true);
            response.put("message", "captured");
            response.put("warnings", new ArrayList<String>());
            response.put("errors", new ArrayList<String>());
            return response;
        }
    }

    static class PassingFixture {
        @Test
        void passes() {
        }
    }

    static class PassingAndFailingFixture {
        @Test
        void passes() {
        }

        @Test
        void fails() {
            throw new AssertionError("expected failure");
        }
    }

    static class FirstForkFixture {
        @Test
        void firstFork() {
        }
    }

    static class SecondForkFixture {
        @Test
        void secondFork() {
        }
    }
}
