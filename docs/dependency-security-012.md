# Backend dependency security remediation

Reviewed on 2026-10-05 from clean `master` at `539e50f`, addressing finding A2 in the [engineering quality and security audit](audit-006.md). Advisory severity below is the upstream rating; it is not a demonstrated severity for an Issunexa route.

## Supported version selection

[Spring's stable releases](https://github.com/spring-projects/spring-boot/releases) and [published parent metadata](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml) still identify **4.1.1** as the latest stable Spring Boot release. 4.1.2 is a snapshot and 4.2.0-M2 a preview, so there is no stable parent/BOM upgrade that supplies these fixes. Retain the stable parent and use its [documented version properties](https://docs.spring.io/spring-boot/maven-plugin/using.html#using.parent-pom) for three narrow maintenance overrides in [pom.xml](../backend/pom.xml): `tomcat.version`, `jackson-2-bom.version` and `jackson-bom.version`.

Tomcat **11.0.26** is a released patch on Boot's [supported Servlet 6.1 / Tomcat 11.0.x line](https://docs.spring.io/spring-boot/system-requirements.html). Jackson **2.21.7** and **3.1.7** are the latest released patches on the existing [LTS branches](https://github.com/FasterXML/jackson/wiki/Jackson-Releases); both were [released](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21.7) on [September 21](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.7). Their 2.21.8 / 3.1.8 successors are under development. The newer non-LTS 2.22 / 3.2 branches are unnecessary for this remediation.

Each Jackson override selects an entire upstream BOM, rather than individual core/databind jars. The resolved graph and packaged application confirm these versions:

| Dependency family | Before | After |
| --- | --- | --- |
| Spring Boot parent, starters and packaging plugin | 4.1.1 | 4.1.1 |
| Tomcat embedded core, EL and WebSocket | 11.0.24 | 11.0.26 |
| Jackson 2 core, databind, YAML and JSR310 | 2.21.5 | 2.21.7 |
| Jackson 3 core and databind | 3.1.5 | 3.1.7 |
| Shared `com.fasterxml.jackson.core:jackson-annotations` | 2.21 | 2.21 |
| springdoc / Swagger core / Flyway | 3.1.1 / 2.2.55 / 12.4.0 | Unchanged |
| Spring Framework / Security / Data JPA | 7.0.9 / 7.1.1 / 4.1.1 | Unchanged |

Annotations intentionally retain the version selected by both Jackson BOMs. Exactly nine resolved artifacts change version; artifact identities and scopes are unchanged. No production dependency is added or removed. Overrides are an application-verified compatibility choice, not a claim that Spring tested this exact combination. Reconsider them with the next stable Boot maintenance release; remove them only when its managed versions include the fixes and the same verification passes.

## Every advisory from the baseline dependency scan

All ten baseline matches are **patched**, without exclusions or not-applicable waivers. The last column records reachability evidence separately from that disposition. The application evidence is [SecurityConfiguration](../backend/src/main/java/io/github/panteliszara/issunexa/shared/security/SecurityConfiguration.java), [authentication controller](../backend/src/main/java/io/github/panteliszara/issunexa/auth/api/AuthenticationController.java), request records and the [runtime configuration](../backend/src/main/resources/application.yml).

| Advisory | Upstream severity / patched maintenance version | Issunexa reachability review |
| --- | --- | --- |
| [GHSA-9xv2-5v5q-p794](https://github.com/advisories/GHSA-9xv2-5v5q-p794), DIGEST replay | Critical; Tomcat 11.0.25 | Spring Security authenticates JSON credentials; no Tomcat DIGEST authenticator configured. |
| [GHSA-gcx9-497g-6cp6](https://github.com/advisories/GHSA-gcx9-497g-6cp6), constraint ordering | Critical; Tomcat 11.0.25 | Filter/method authorization; no container declarative security constraints configured. |
| [GHSA-h3x4-894j-xpx5](https://github.com/advisories/GHSA-h3x4-894j-xpx5), FORM redirect constraints | Critical; Tomcat 11.0.25 | REST login with Spring form login disabled; no Tomcat FORM login or method constraints. |
| [GHSA-7hhh-6rmp-j9qf](https://github.com/advisories/GHSA-7hhh-6rmp-j9qf), unbounded DataInput error token | High; Jackson 2.21.7 / 3.1.7 | MVC reads InputStream/Reader. Application and inspected Swagger/Flyway entry points do not parse attacker-controlled DataInput. |
| [GHSA-p6pp-m3f8-5c89](https://github.com/advisories/GHSA-p6pp-m3f8-5c89), number recognition CPU growth | High; Jackson 2.21.7 / 3.1.7 | Public request records contain strings/enums, no numeric JSON fields or application calls to `looksLikeValidNumber`. Third-party generic JSON parsing exists; reachability is not cleared globally. |
| [GHSA-cxp5-3px4-pw24](https://github.com/advisories/GHSA-cxp5-3px4-pw24), object-ID forward references | High; Jackson 2.21.7 / 3.1.7 | No identity-enabled request collections/maps. Swagger inspects identity annotations for schema generation; that is not an exposed identity-binding request route. |
| [GHSA-gx83-3vf8-gh7j](https://github.com/advisories/GHSA-gx83-3vf8-gh7j), Comparable type validation | Moderate; Jackson 2.21.6 / 3.1.6 | No polymorphic Comparable request fields or default typing configured by the application. |
| [GHSA-q4xh-88c3-wmh7](https://github.com/advisories/GHSA-q4xh-88c3-wmh7), XML duration/calendar parsing | High; Jackson 2.21.6 / 3.1.6 | No `javax.xml.datatype.Duration` / `XMLGregorianCalendar` request fields. Swagger's type catalogue supports calendar schemas; it does not add such a request field. |
| [GHSA-wjgm-6hv5-3cvf](https://github.com/advisories/GHSA-wjgm-6hv5-3cvf), Path provider selection | Moderate; Jackson 2.21.6 / 3.1.6 | No JSON-bound Path request fields. Flyway/Swagger filesystem paths are internal resource/configuration operations, not application request DTOs. |
| [GHSA-wv8q-qhhj-9h54](https://github.com/advisories/GHSA-wv8q-qhhj-9h54), unknown type-ID cache growth | High; Jackson 2.21.7 / 3.1.7 | No name-based polymorphic fallback request fields. Swagger converts locally generated schemas/annotation values; Flyway parses configuration/internal JSON. No attacker-fed long-lived fallback mapper was established. |

Matching published source jars were inspected for Spring Web 7.0.9, springdoc 3.1.1, Swagger core/models 2.2.55 and Flyway 12.4.0. In particular, Spring's `AbstractJacksonHttpMessageConverter.readJavaType` passes an InputStream or Reader; springdoc/Swagger conversions use generated schema objects and annotation examples; Flyway `JsonUtils` uses JSON strings for internal data. This focused review does not prove that every possible third-party path is safe. Patched runtime dependencies remain necessary even where a current application prerequisite was not found.

## Current vendor notices beyond the scanner

[Tomcat's security page](https://tomcat.apache.org/security-11.html) rates the three scanner Critical items differently: CVE-2026-65905 and CVE-2026-68525 are Low; CVE-2026-65182 is Important. Both upstream assessments are retained, without translating them into a confirmed Issunexa exploit. The following vendor notices affect baseline 11.0.24; **all are fixed in selected 11.0.26**, including notices absent from OSV:

| Vendor notices | Vendor severity | Runtime evidence / fixed release |
| --- | --- | --- |
| CVE-2026-65905, CVE-2026-68525; CVE-2026-65182 | Low; Important | The three baseline Tomcat advisories above; 11.0.25. |
| CVE-2026-73180, CVE-2026-66299; CVE-2026-87022; CVE-2026-79677; CVE-2026-77791, CVE-2026-76183 | Low; Low; Moderate; Important | No WebSocket endpoints or example application; embedded WebSocket jar is present and upgraded. First two fixed in 11.0.25, remaining four in 11.0.26. |
| CVE-2026-68763; CVE-2026-86350; CVE-2026-78437, CVE-2026-77762 | Important; Important; Low | HTTP/2 is not enabled; Nginx proxies HTTP/1.1. First fixed in 11.0.25, remaining three in 11.0.26. |
| CVE-2026-65637; CVE-2026-86248, CVE-2026-73581 | Moderate | Local HTTP without SNI, CLIENT_CERT, OpenSSL/FFM TLS configuration. First fixed in 11.0.25, others in 11.0.26. |
| CVE-2026-68569; CVE-2026-66422; CVE-2026-75973 | Important; Low; Low | No DataSourceRealm, servlet role references or multi-context Jakarta Authentication provider. First two fixed in 11.0.25, last in 11.0.26. |
| CVE-2026-65927; CVE-2026-65183; CVE-2026-78383 | Important; Low; Important | No RewriteValve, Unix-domain connector or AJP connector. First two fixed in 11.0.25, last in 11.0.26. |
| CVE-2026-77756, HTTP/1.0 Transfer-Encoding handling | Low | HTTP/1.x server behind a reverse proxy is present; potential relevant path, no cross-request failure reproduced. Fixed in 11.0.26. |

[FasterXML core notices](https://github.com/FasterXML/jackson-core/security/advisories) include an additional **High** advisory, [GHSA-649p-m576-vr99](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-649p-m576-vr99), missing from the baseline OSV response. It delays field-name bounds on Reader/String input, fixed in 2.21.6 / 3.1.6 and included in our patches. The converter's Reader/character-decoding path makes this relevant to request parsing, even though normal UTF-8 parsing uses byte input. The [bounded regression](../backend/src/test/java/io/github/panteliszara/issunexa/shared/web/JacksonParsingSecurityTests.java) confirms both original mappers consume all 200,009 supplied characters before rejection; both patched mappers reject before 100,000. This measures early enforcement without a memory-exhaustion or timing test. [HTTP regressions](../backend/src/test/java/io/github/panteliszara/issunexa/auth/api/AuthenticationIntegrationTests.java) also cover oversized names in UTF-8, UTF-16 and ISO-8859-1 login bodies, generic `400` Problem Details and no authenticated session. No service-level denial of service was attempted.

The 9 core and 17 [databind vendor notices](https://github.com/FasterXML/jackson-databind/security/advisories) were compared with both resolved generations. Earlier notices are already fixed on these branches. One metadata discrepancy is explicit: [GHSA-5gvw-p9qm-jgwh](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-5gvw-p9qm-jgwh) has a broad `2.21.0–2.22.0` interval overlapping its stated 2.21.5 fix. The [2.21.5 release notes](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21.5) list the CVE-2026-59889 fix, and published 2.21.7 `UnwrappedPropertyHandler.processUnwrapped` contains the active-view visibility guard. Disposition: patched on this maintenance branch, not a waived applicability claim. Issunexa additionally has no JsonView/JsonUnwrapped request fields.

Current [binary-format notices](https://github.com/FasterXML/jackson-dataformats-binary/security/advisories) and pending 2.21.8 / 3.1.8 release notes mention CBOR/Smile name limits and Avro/Protobuf/Smile allocation defects (CVE-2026-68495, CVE-2026-104015, CVE-2026-104016, CVE-2026-104017, CVE-2026-104895). Their format artifacts are **absent** from the resolved graph and packaged application; no exception to a present vulnerable jar is needed. The [TOML notice](https://github.com/FasterXML/jackson-dataformats-text/security/advisories/GHSA-7vh9-hpf2-3qw2) also concerns an absent artifact. Resolved YAML is updated through the Jackson 2 BOM; JSR310's vendor advisory feed has no published entries.

The current Spring notices [CVE-2026-41707](https://spring.io/security/cve-2026-41707/), [CVE-2026-47841](https://spring.io/security/cve-2026-47841/), [CVE-2026-47842](https://spring.io/security/cve-2026-47842/) and [CVE-2026-47877](https://spring.io/security/cve-2026-47877/) are already fixed in resolved Security 7.1.1; [CVE-2026-47834](https://spring.io/security/cve-2026-47834/) is fixed in resolved Data JPA 4.1.1. No OAuth/DPoP/WebAuthn/encryption architecture or native-query sorting is added by this change.

## Verification and residual disposition

- Before and after: `./mvnw --batch-mode --no-transfer-progress -DskipTests dependency:tree`, inspection of the packaged `BOOT-INF/lib` jars and [OSV's batch version query](https://google.github.io/osv.dev/api/) for all 147 resolved compile/runtime/test artifacts. Before: five matched artifacts, ten distinct baseline advisories. After: **zero matches**. Vendor cross-check adds the patched Reader advisory and the Tomcat notices above; scanner silence alone was not used as a disposition.
- Two early-rejection regressions fail on the original Jackson BOMs with temporary command-line version properties; they pass on the selected patches. Initial focused authentication/authorization/OpenAPI checks pass 60 tests; the added parser/HTTP regressions and authentication suite pass 37 tests. Final `./mvnw --batch-mode --no-transfer-progress verify` with Java 21 and real PostgreSQL/Testcontainers passes **516 tests**, zero failures/errors/skips.
- From `frontend/` with Node 24.21.0: `npm ci`, `npm run lint`, `npm run typecheck:e2e`, `npm test` (**342 tests**) and `npm run build` pass. Real-stack `npm run test:e2e` passes **7/7 tests** with Playwright 1.63.0 / Chromium 153.0.8010.12: six desktop journeys and one Pixel 7 emulation journey. Its containers, network, volume and project image tags are confirmed removed. Emulation does not establish physical-device or Safari coverage.
- All three changed Markdown documents render, their 52 local links resolve, the upstream parent-POM documentation anchor resolves, Maven XML parses and `git diff --check` passes. Scanner responses, source downloads, dependency trees, build output and transient reports remain outside the commit. Existing independent Backend, Frontend and E2E CI must pass before merge.

No residual advisory match against a present resolved artifact was identified after reconciling vendor maintenance fixes. The broad-range metadata discrepancy and absent-format notices remain documented. These are date-specific dependency findings, not a vulnerability-free guarantee or public-deployment approval. Database privilege separation and login-attempt handling were separate follow-ups at this dependency-remediation snapshot. They have since been implemented; see [database roles](database.md#database-roles-and-provisioning) and [bounded login attempts](security.md#bounded-login-attempts). This dependency change did not redesign authentication, authorization, persistence, frontend or CI.
