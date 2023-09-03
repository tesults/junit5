package com.tesults.junit5;

import org.junit.jupiter.api.TestInfo;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestTag;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

import com.tesults.tesults.*;
import org.junit.platform.launcher.TestPlan;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

public class TesultsListener implements TestExecutionListener {
    List<Map<String,Object>> cases = new ArrayList<Map<String, Object>>();
    Map<String, Long> startTimes = new HashMap<String, Long>();

    static Map<String, List<String>> files = new HashMap<String, List<String>>();

    Boolean disabled = false;

    // Options
    String config = System.getProperty("tesultsConfig");
    String target = System.getProperty("tesultsTarget");

    String filesDir = System.getProperty("tesultsFiles");
    Boolean nosuites = System.getProperty("tesultsNoSuites") == null ? false : true;
    String buildName = System.getProperty("tesultsBuildName");
    String buildDesc = System.getProperty("tesultsBuildDesc");
    String buildResult = System.getProperty("tesultsBuildResult");
    String buildReason = System.getProperty("tesultsBuildReason");

    public List<String> filesForCase(String suite, String name) {
        if (filesDir == null) {
            return null;
        }
        List<String> caseFiles = new ArrayList<String>();
        String pathString = Paths.get(filesDir, name).toString();
        if (!suite.equals("") && suite != null) {
            pathString = Paths.get(this.filesDir, suite, name).toString();
        }
        File path = new File(pathString);
        try {
            File[] files = path.listFiles();
            for (File file : files) {
                if (!file.isDirectory()) {
                    if (!file.getName().equals(".DS_Store")) { // Ignore os files
                        caseFiles.add(file.getAbsolutePath());
                    }
                }
            }
        } catch (NullPointerException ex) {
            // Dereference of listFiles can produce this.
        } catch (Exception ex) {
            System.out.println("Exception1: " + ex.toString());
        }
        return caseFiles;
    }

    public void testPlanExecutionStarted(TestPlan testPlan) {
        if (target == null) {
            System.out.println("Tesults disabled - target not provided.");
            disabled = true;
            return;
        }

        if (config != null) {
            FileInputStream in = null;
            try {
                Properties props = new Properties();
                in = new FileInputStream(System.getProperty("tesultsConfig"));
                props.load(in);
                if (props.getProperty(target, null) != null) {
                    target = props.getProperty(target);
                    if (target.equals("")) {
                        System.out.println("Invalid target value in configuration file");
                    }
                }
                if (filesDir == null) {
                    filesDir = props.getProperty("tesultsFiles", null);
                }
                if (nosuites == false) {
                    String nosuitesConfig = props.getProperty("tesultsNoSuites", null);
                    if (nosuitesConfig != null) {
                        if (nosuitesConfig.toLowerCase().equals("true")) {
                            nosuites = true;
                        }
                    }
                }
                if (buildName == null) {
                    buildName = props.getProperty("tesultsBuildName", null);
                }
                if (buildDesc == null) {
                    buildDesc = props.getProperty("tesultsBuildDesc", null);
                }
                if (buildResult == null) {
                    buildResult = props.getProperty("tesultsBuildResult", null);
                }
                if (buildReason == null) {
                    buildReason = props.getProperty("tesultsBuildReason", null);
                }
            } catch (FileNotFoundException e) {
                System.out.println("Configuration file specified for Tesults not found");
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

    }

    public void executionStarted(TestIdentifier testIdentifier) {
        if (disabled) {
            return;
        }
        if (testIdentifier.isTest()) {
            startTimes.put(testIdentifier.getUniqueId(), java.lang.System.currentTimeMillis());
        }
    }

    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        if (disabled) {
            return;
        }
        if (testIdentifier.isTest()) {
            String result = testExecutionResult.getStatus().toString();
            if (result.equals("SUCCESSFUL")) {
                result = "pass";
            } else if (result.equals("FAILED")) {
                result = "fail";
            } else {
                result = "unknown";
            }

            String reason = "";
            if (testExecutionResult.getThrowable().isPresent()) {
                reason = testExecutionResult.getThrowable().get().getMessage();
            }

            String suite = null;

            String name = testIdentifier.getDisplayName();
            int index = name.lastIndexOf("()");
            if (index > -1) {
                name = name.substring(0,  index);
            }
            int index2 = name.lastIndexOf("(TestInfo)");
            if (index2 > -1) {
                name = name.substring(0, index2);
            }

            Map<String, Object> testCase = new HashMap<String, Object>();
            testCase.put("name", name);
            testCase.put("result", result);
            //testCase.put("suite", suite);
            testCase.put("reason", reason);
            if (startTimes.get(testIdentifier.getUniqueId()) != null) {
                testCase.put("start", startTimes.get(testIdentifier.getUniqueId()));
            }
            testCase.put("end", java.lang.System.currentTimeMillis());

            // suite, desc and custom fields
            for (TestTag tag : testIdentifier.getTags()) {
                String tagVal = tag.getName();
                // desc
                if (tagVal.indexOf("desc=") == 0) {
                    testCase.put("desc", tagVal.substring(5));
                }
                // custom
                int indexUnderScore = tagVal.indexOf("_");
                int indexEquals = tagVal.indexOf("=");
                if (indexUnderScore == 0 && indexEquals > 0 && indexEquals < tagVal.length()) {
                    String customName = tagVal.substring(0, indexEquals);
                    String customValue = tagVal.substring(indexEquals + 1);
                    testCase.put(customName, customValue);
                }
                // suite
                if (tagVal.indexOf("suite=") == 0) {
                    suite = tagVal.substring(6);
                    testCase.put("suite", suite);
                }
            }

            // Suite
            if (suite == null && nosuites != true) {
                String separator = "class:";
                if (testIdentifier.getParentId().isPresent()) {
                    suite = testIdentifier.getParentId().get();
                    if (suite.indexOf("/[test-template:") == -1) {
                        suite = suite.substring(suite.indexOf(separator) + separator.length(), suite.lastIndexOf("]"));
                        index = suite.lastIndexOf('.');
                        if (index > -1) {
                            suite = suite.substring(index + 1);
                        }
                    } else {
                        // parameterized test case
                        suite = suite.substring(0, suite.indexOf("/[test-template:"));
                        suite = suite.substring(suite.indexOf(separator) + separator.length(), suite.lastIndexOf("]"));
                        index = suite.lastIndexOf('.');
                        if (index > -1) {
                            suite = suite.substring(index + 1);
                        }
                    }
                }
                testCase.put("suite", suite);
            }

            // Files:
            List<String> testFiles = filesForCase(suite == null ? "" : suite, name);
            if (testFiles != null) {
                if (testFiles.size() > 0) {
                    testCase.put("files", testFiles);
                }
            }

            // Enhanced reporting files:
            List<String> paths = files.get(testIdentifier.getDisplayName());
            if (paths != null) {
                List<String> existingPaths = (List<String>) testCase.get("files");
                if (existingPaths != null) {
                    for (String path: existingPaths) {
                        paths.add(path);
                    }
                }
                testCase.put("files", paths);
            }

            cases.add(testCase);
        }
    }

    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (disabled) {
            return;
        }
        // Add build case
        if (buildName != null) {
            if (buildName.equals("")) {
                buildName = "-";
            }
            Map<String, Object> buildCase = new HashMap<String, Object>();

            buildCase.put("name", buildName);
            buildCase.put("suite", "[build]");

            if (buildDesc != null) {
                if (buildDesc.equals("")) {
                    buildDesc = "-";
                }
                buildCase.put("desc", buildDesc);
            }
            if (buildReason != null) {
                if (buildReason.equals("")) {
                    buildReason = "-";
                }
                buildCase.put("reason", buildReason);
            }
            if (buildResult != null) {
                if (buildResult.toLowerCase().equals("pass")) {
                    buildCase.put("result", "pass");
                } else if (buildResult.toLowerCase().equals("fail")) {
                    buildCase.put("result", "fail");
                } else {
                    buildCase.put("result", "unknown");
                }
            } else {
                buildCase.put("result", "unknown");
            }

            // Files:
            List<String> files = filesForCase("[build]", buildName);
            if (files != null) {
                if (files.size() > 0) {
                    buildCase.put("files", files);
                }
            }

            cases.add(buildCase);
        }

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("target", target);

        Map<String, Object> results = new HashMap<String, Object>();
        results.put("cases", cases);
        data.put("results", results);

        //System.out.println(data.toString());

        // Upload
        System.out.println("Tesults results upload...");
        Map<String, Object> response = Results.upload(data);
        System.out.println("success: " + response.get("success"));
        System.out.println("message: " + response.get("message"));
        System.out.println("warnings: " + ((List<String>) response.get("warnings")).size());
        System.out.println("errors: " + ((List<String>) response.get("errors")).size());
    }

    // Enhanced reporting

    public static void file (TestInfo testinfo, String path) {
        try {
            List<String> paths = files.get(testinfo.getDisplayName());
            if (paths == null) {
                paths = new ArrayList<String>();
            }
            paths.add(path);
            files.put(testinfo.getDisplayName(), paths);
        } catch (Exception ex) {

        }
    }
}