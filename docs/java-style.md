# Java style

General best practice for modern Java (21+) and Spring Boot, followed by the rules specific to
this project. AGENTS.md comes first; where "This project" differs from the general guidance,
"This project" wins.

## Formatting and naming
- Let the formatter decide layout (google-java-format via Spotless here); never hand-format or
  argue about whitespace in review.
- Treat compiler warnings as errors. Fix the cause; use `@SuppressWarnings` only on the smallest
  scope, with a comment saying why.
- Classes are nouns (`PriceService`), methods are verbs (`price`, `load`), booleans read as
  questions (`isCapped`, `hasTarget`). Constants are `UPPER_SNAKE_CASE`.
- No abbreviations except well-known ones (`id`, `url`, `json`). A longer clear name beats a
  comment explaining a short one.
- Put units in names when a number has one: `timeoutMs`, `tempC`, `notionalGbp`.

## Design
- Small classes with one reason to change; small methods that do one thing at one level of
  abstraction.
- Prefer composition over inheritance. Make classes `final` unless designed for extension
  (framework-managed classes that are subclassed at runtime, such as Spring `@Configuration`,
  are exempt).
- Keep visibility as narrow as possible: private by default, package-private before public.
- Depend on interfaces at boundaries (I/O, clocks, external services) so tests can substitute
  them; don't add interfaces with a single implementation elsewhere.
- No static mutable state. Static methods are fine for pure functions.
- Avoid boolean flag parameters; use an enum or two methods.

## Immutability and values
- Use `record`s for data carriers. Validate in the compact constructor and fail fast.
- Make fields `final`. Defensively copy collections passed in (`List.copyOf`) and never return a
  mutable internal collection.
- Use `java.time` (`LocalDate`, `Instant`, `Duration`), never `Date`/`Calendar`; be explicit about
  time zones.
- Use `BigDecimal` (with an explicit `RoundingMode`) where decimal exactness matters, such as
  money that settles or rounding a contract specifies; `double` is fine for statistical models.
- Prefer enums and sealed interfaces over string or int codes; use pattern-matching `switch` with
  no `default`, so the compiler flags a missing case.

## Null and Optional
- Don't return `null` from public methods; return an empty collection or an `Optional`.
- `Optional` is for return types. Don't use it for fields, parameters or collection elements.
  Prefer `OptionalInt`/`OptionalDouble` for primitives.
- Check required arguments with `Objects.requireNonNull(x, "x")` at public boundaries.

## Exceptions
- Use unchecked exceptions for programming errors (`IllegalArgumentException`,
  `IllegalStateException`) and domain-specific exceptions for expected failures (bad data,
  missing resources).
- Messages name the field or input and the bad value, so the log alone explains the failure.
- Never swallow an exception. Catch only what you can handle; when wrapping, keep the cause.
- Don't use exceptions for normal control flow.
- Validate at boundaries; inside, trust the validated types.

## Collections and streams
- Use the interface type (`List`, `Map`) in signatures. Create small immutable collections with
  `List.of` / `Map.of`.
- Streams for clear transform/filter/collect pipelines; a plain loop when it is easier to read or
  needs early exit or checked exceptions. No side effects inside stream lambdas.
- Use `var` only when the type is obvious from the right-hand side.

## Concurrency
- Prefer immutable objects and stateless services; they are thread-safe for free.
- Use `java.util.concurrent` (executors, concurrent collections) or virtual threads rather than
  raw `Thread`/`synchronized` blocks. Never share a mutable object between requests.

## Documentation
- Javadoc on every public type and member: what it does, units, valid ranges, `@throws`
  conditions. Don't restate the signature.
- Comments explain why, not what. Link the spec, ADR or ticket that a non-obvious rule comes
  from.

## Logging
- SLF4J with `{}` placeholders, never string concatenation. One logger per class,
  `private static final`.
- `ERROR` for failures needing attention (with the exception), `WARN` for recoverable anomalies,
  `INFO` for lifecycle events, `DEBUG` for detail. Don't log and rethrow the same exception.
- Never log secrets, credentials or full request bodies at `INFO`.

## Testing (JUnit 5+)
- Test behaviour, not implementation. One behaviour per test; the name says the condition and the
  expected result.
- Arrange / act / assert. No logic (loops, ifs) in assertions; use `@ParameterizedTest` for
  tables of cases, with a `name` that identifies each case.
- `assertThrows` and then check the message names the field or input at fault.
- Compare doubles with a tolerance unless the value is exact by construction.
- Tests are deterministic: fixed seeds, injected clocks, no sleeping, no network, temporary
  folders (`@TempDir`) for files.
- Prefer real objects and hand-written fakes over mocks; mock only at boundaries you don't own.
- A pricing or numerical formula gets a known-answer test stating where the number comes from.

## Spring Boot
- Constructor injection with `private final` fields; no field or setter `@Autowired`.
- Layering: controllers are thin (HTTP mapping only), services hold orchestration, the domain has
  no framework dependencies.
- Request and response bodies are DTO records; never expose domain or persistence types as JSON.
- Typed configuration with `@ConfigurationProperties` records and defaults, not scattered `@Value`.
  Document each property.
- Validate input at the boundary (Bean Validation `@Valid` is the default choice).
- Central error handling with `@RestControllerAdvice`; return a consistent error body (RFC 9457
  `ProblemDetail` by default). Never leak stack traces, class names or internal paths.
- Declare `consumes`/`produces` on endpoints; reject unknown JSON fields rather than ignore them.
- `@Bean` methods only wire objects. Add `@Transactional`, `@Async`, caching or scheduling only
  when needed, and understand their proxy semantics (no self-invocation).
- Use Actuator for health and metrics in deployed services; expose only what is needed.
- Tests: slice tests (`@WebMvcTest`) for fast controller checks, `@SpringBootTest` for wiring and
  end-to-end behaviour.
- Bind to the narrowest network interface needed; secrets come from the environment, never from
  committed properties.

## This project
These rules are specific to `pricer/` and override the general guidance above where they differ.

- **Layering.** Spring is used only in `pricer.api`. `contract`, `index`, `model` and `data`
  import no Spring and stay usable as a plain library. `PricerApplication` sits in `api`, so
  component scanning covers only `api`.
- **Units and rounding.** Units in names as in AGENTS.md (`tempC`, `hddDegC`, `tickGbpPerDegC`).
  Money is `double` GBP in the models; daily-HDD settlement rounding is done in exact decimal
  (ADR 0004).
- **Errors.** Bad input → `IllegalArgumentException`; bad data → `TemperatureDataException`,
  naming the season, date or column. The library knows nothing about HTTP; status mapping is only
  in `ApiExceptionHandler`. Error bodies are `{error, message}`, not `ProblemDetail`: the
  contract is fixed by spec 006.
- **JSON.** Strict reading is configured once in `StrictJsonConfig` (unknown fields, coercions,
  enum ordinals are 400s); don't loosen it per endpoint.
- **Validation.** Record constructors validate; no Bean Validation, so error messages have one
  source.
- **Endpoint tests** are `@SpringBootTest` + `MockMvc` against real Parquet fixtures, no slices,
  no Mockito. Assert the exact JSON field set, so a renamed or added field fails a test.
- **Test names.** `condition_expectedResult`; a test that proves a spec criterion is prefixed with
  it (`ac5_...`, `amendmentA_...`).
- **Health.** A plain `GET /health`; no Actuator (it would be a new dependency).
- **Network.** Bind to `127.0.0.1` until a spec says otherwise. A new starter is a new dependency:
  give the reason in the PR.
- **Javadoc** names the spec or ADR a rule comes from: `(spec 006, decision 2)`.
