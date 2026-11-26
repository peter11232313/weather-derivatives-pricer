# Java style

Applies to `pricer/`. AGENTS.md rules come first; this file adds how Java code is written.
Formatting is google-java-format via Spotless (`./gradlew spotlessApply`); don't hand-format.
`-Xlint:all -Werror` is on, so warnings fail the build.

## Types and values
- Value types are `record`s. The compact constructor validates: `Objects.requireNonNull(x, "x")`,
  then `IllegalArgumentException` whose message names the field and the bad value.
- Records copy collection inputs (`List.copyOf`). Nothing public returns a mutable collection.
- Stateless library code is a `final class` with a private constructor and static methods.
- No `null` in the library API. Optional terms use `OptionalDouble` / `OptionalInt` /
  `Optional<T>`; `null` is only allowed in JSON DTOs (`"detrended": null`).
- Units in names (`tempC`, `hddDegC`, `tickGbpPerDegC`), as in AGENTS.md. Money is `double` GBP,
  and settlement rounding is done in exact decimal (ADR 0004).
- Prefer `java.time` types (`LocalDate`, `YearMonth`) over ints or strings.

## Errors
- Bad input → `IllegalArgumentException`. Bad data → `TemperatureDataException`, naming the
  season, date or column. Never catch and ignore; never return NaN in place of an error.
- The library knows nothing about HTTP status codes; mapping happens only in `api` (see below).

## Docs
- Every public type, constructor and method has Javadoc, including Spring-created classes
  ("Created by Spring."). Name the spec or ADR a rule comes from: `(spec 006, decision 2)`.
- `@throws` lists each condition that throws.

## Tests
- JUnit 6, no mocking library. Fakes are hand-written (`TemperatureSource` lambda,
  `@TestConfiguration`).
- Method names are `acN_condition_expectedResult` and map to spec criteria; parameterized tests
  set `name = "[{index}] ..."`.
- Files go in `@TempDir`; never read `data/`. Assert exact values with an explicit tolerance;
  a known-answer test states where the number comes from.

## Spring Boot (`api` package only)
- Spring stays in `pricer.api`. `contract`, `index`, `model` and `data` import no Spring and stay
  usable as a plain library. `PricerApplication` sits in `api`, so component scanning covers
  only `api`.
- Constructor injection with `private final` fields; no `@Autowired` on fields or setters.
- Controllers are thin: map the request, call the service, return a DTO. Services map DTOs ↔
  library calls and contain no pricing maths.
- Request and response bodies are DTO records in `api`. Never expose library types directly as
  JSON (field names would change with library refactors).
- Configuration is a `@ConfigurationProperties` record with `@DefaultValue`, not `@Value`.
  Document every property in `application.properties`.
- Errors go through one `@RestControllerAdvice`. Bodies are `{error, message}`; no stack traces,
  exception class names or internals. Unexpected exceptions are logged and return a fixed 500
  message.
- JSON is strict (`StrictJsonConfig`): unknown fields, coercions and enum ordinals are 400s. Don't
  loosen it per endpoint.
- Declare `consumes` / `produces` on every mapping.
- Logging is SLF4J with `{}` placeholders. Log 5xx with the stack trace; don't log 4xx above
  `DEBUG`. Never log request bodies at `INFO`.
- `@Bean` methods only wire objects; no business logic in them.
- Add `@Transactional`, `@Async`, caching or scheduling only with a spec. The API is stateless and
  reads files per request.
- Endpoint tests are `@SpringBootTest` + `MockMvc` against real Parquet fixtures, not mocks.
  Assert the exact JSON field set, so a renamed or added field fails a test.
- Bind to `127.0.0.1` until a spec says otherwise. Adding a starter is a new dependency: give the
  reason in the PR.

## Deliberately not used
- `@WebMvcTest` slices: endpoint tests cover the real wiring end to end.
- Lombok, MapStruct: records are enough, and both are new dependencies.
- Bean Validation (`@Valid`): record constructors already validate; a second rule set would
  duplicate error messages.
