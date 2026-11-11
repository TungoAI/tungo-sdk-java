# Examples

`kz/tungo/examples/ReadmeExamples.java` holds every code sample of the main README. The build adds this
directory as a test source root, so `mvn verify` compiles the examples with the same strict compiler
settings as the library, and `ReadmeExamplesTest` runs each of them against a local mock server.

To try one against the real API, copy the method body into your project, add the dependency from
the main README and set `TUNGO_API_KEY`.
