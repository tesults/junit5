# Tesults JUnit5 Listener

The Tesults JUnit5 Listener makes it easy to push results data to Tesults from your JUnit5 tests.


## Documentation

Documentation is available at https://www.tesults.com/docs/junit5


## GitHub Actions

`tesults-junit5` can produce a local results file for the Tesults Test
Automation Reporting action. No Tesults target token is required for this mode.

Use `tesults-junit5` 1.3.0 or later and keep automatic listener detection
enabled in your test configuration:

```groovy
dependencies {
    testImplementation 'com.tesults.junit5:tesults-junit5:1.3.0'
}

test {
    useJUnitPlatform()
    systemProperty 'junit.jupiter.extensions.autodetection.enabled', 'true'
}
```

Add the action before the test step:

```yaml
- uses: tesults/test-automation-reporting@v1
- run: ./gradlew test
```

The action sets `TESULTS_OUTPUT_FILE` automatically. The listener also accepts
`tesultsOutputFile` as a JVM system property or configuration-file property;
the environment variable takes precedence. If both local output and
`tesultsTarget` are configured, the listener writes the local report and
uploads the same test run to Tesults.


## Support

help@tesults.com
