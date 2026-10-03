Plain-JVM logic tests (no Android needed). From the repo root, with kotlinc (JAVA_OPTS=-Xmx2g):
  kotlinc tests/Stubs.kt app/src/main/java/com/pragon/mobile/{Calc,Typer,VoiceParser}.kt tests/ParserTest.kt  -include-runtime -d p1.jar && java -jar p1.jar
  kotlinc tests/Stubs.kt app/src/main/java/com/pragon/mobile/{Calc,Typer,VoiceParser}.kt tests/ParserTest2.kt -include-runtime -d p2.jar && java -jar p2.jar
  kotlinc app/src/main/java/com/pragon/mobile/Calc.kt tests/CalcTest.kt -include-runtime -d calc.jar && java -jar calc.jar
GuardTest.kt (privacy-shield host rules) also needs android.jar + okhttp on the classpath, so it is run in CI only if you add them.
