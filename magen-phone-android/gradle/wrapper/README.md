# Gradle Wrapper bootstrap

The source archive did not contain a Wrapper JAR. `gradlew` and `gradlew.bat`
therefore bootstrap the official Gradle 8.13 Wrapper JAR on first use and verify
its SHA-256 before execution. The Gradle 8.13 distribution is also pinned by
`distributionSha256Sum` in `gradle-wrapper.properties`.

After the first successful run, commit/include `gradle-wrapper.jar` in future
source archives so the project uses the conventional fully self-contained
Gradle Wrapper layout.
