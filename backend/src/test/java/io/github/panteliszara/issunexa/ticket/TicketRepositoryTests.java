package io.github.panteliszara.issunexa.ticket;

import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserAccountRepository;
import io.github.panteliszara.issunexa.user.UserAccountService;
import io.github.panteliszara.issunexa.user.UserRole;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@Transactional
class TicketRepositoryTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UserAccount requester;

    @ParameterizedTest
    @EnumSource(TicketCategory.class)
    void categoryRoundTripsAsStringAndSurvivesClaimAndStatus(TicketCategory category) {
        Ticket ticket = ticketRepository.saveAndFlush(new Ticket("VPN", "Unavailable", TicketStatus.OPEN,
                TicketPriority.HIGH, category, requester));
        Long id = ticket.getId();
        entityManager.clear();
        assertThat(ticketRepository.findById(id).orElseThrow().getCategory()).isEqualTo(category);
        assertThat(jdbcTemplate.queryForObject("SELECT category FROM tickets WHERE id = ?", String.class, id))
                .isEqualTo(category.name());

        ticketService.claimTicket(id, requester.getEmail());
        ticketRepository.flush();
        ticketService.changeStatus(id, TicketStatus.IN_PROGRESS, requester.getEmail());
        ticketRepository.flush();
        entityManager.clear();

        Ticket reloaded = ticketRepository.findById(id).orElseThrow();
        assertThat(reloaded.getCategory()).isEqualTo(category);
        assertThat(reloaded.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(reloaded.getRequester().getId()).isEqualTo(requester.getId());
        assertThat(reloaded.getAssignee().getId()).isEqualTo(requester.getId());
        assertThat(reloaded.getVersion()).isEqualTo(2);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"UNSUPPORTED", "incident", ""})
    void rejectsInvalidCategoryThroughDirectSql(String category) {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO tickets (title, description, status, priority, category, version, created_at, updated_at)
                VALUES ('Invalid category', 'Details', 'OPEN', 'HIGH', ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, category)).isInstanceOf(DataIntegrityViolationException.class).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo(category == null ? "23502" : "23514");
                    if (category == null) {
                        assertThat(error.getServerErrorMessage().getColumn()).isEqualTo("category");
                    } else {
                        assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("ck_tickets_category");
                    }
                });
    }

    @Test
    void categoryFilteringComposesWithStatusPrioritySearchAndDeterministicPagination() {
        Ticket first = categoryFixture("Same", TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT);
        Ticket second = categoryFixture("Same", TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT);
        Ticket service = categoryFixture("Same", TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.SERVICE_REQUEST);
        Ticket progressed = categoryFixture("Progressed", TicketStatus.IN_PROGRESS, TicketPriority.HIGH, TicketCategory.INCIDENT);
        Ticket low = categoryFixture("Low", TicketStatus.OPEN, TicketPriority.LOW, TicketCategory.INCIDENT);
        categoryFixture("Access", TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.ACCESS_REQUEST);
        entityManager.clear();

        assertThat(categoryPage(0, 10, null, null, TicketCategory.INCIDENT, null).getContent())
                .extracting(Ticket::getId).containsExactly(low.getId(), progressed.getId(), first.getId(), second.getId());
        assertThat(categoryPage(0, 10, null, null, TicketCategory.SERVICE_REQUEST, null).getContent())
                .extracting(Ticket::getId).containsExactly(service.getId());
        assertThat(categoryPage(0, 10, TicketStatus.IN_PROGRESS, null, TicketCategory.INCIDENT, null).getContent())
                .extracting(Ticket::getId).containsExactly(progressed.getId());
        assertThat(categoryPage(0, 10, null, TicketPriority.LOW, TicketCategory.INCIDENT, null).getContent())
                .extracting(Ticket::getId).containsExactly(low.getId());

        Page<Ticket> page = categoryPage(0, 1, TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, "vPn");
        Page<Ticket> next = categoryPage(1, 1, TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, "vPn");
        assertThat(page.getContent()).extracting(Ticket::getId).containsExactly(first.getId());
        assertThat(next.getContent()).extracting(Ticket::getId).containsExactly(second.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(next.isLast()).isTrue();
        assertThat(categoryPage(0, 10, null, null, TicketCategory.OTHER, null).getTotalElements()).isZero();
        Page<Ticket> unmatched = categoryPage(0, 10, TicketStatus.CLOSED, null, TicketCategory.SERVICE_REQUEST, null);
        assertThat(unmatched.getContent()).isEmpty();
        assertThat(unmatched.getTotalElements()).isZero();
        assertThat(categoryPage(0, 10, null, null, TicketCategory.INCIDENT, "absent").getContent()).isEmpty();
    }

    private Ticket categoryFixture(String title, TicketStatus status, TicketPriority priority, TicketCategory category) {
        return ticketRepository.saveAndFlush(new Ticket(title, "VPN support", status, priority, category, requester));
    }

    private Page<Ticket> categoryPage(int page, int size, TicketStatus status, TicketPriority priority,
            TicketCategory category, String query) {
        return ticketService.listTickets(page, size, status, priority, category, query,
                TicketSortField.TITLE, TicketSortDirection.ASC, requester.getEmail());
    }

    @BeforeEach
    void createRequester() {
        // Staff identity keeps these persistence/query tests independent of ownership scoping.
        requester = userAccountService.createUser("requester@example.com", "Requester",
                "ticket integration test password", UserRole.AGENT);
        userAccountRepository.flush();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                requester.getEmail(), null, List.of(new SimpleGrantedAuthority("ROLE_AGENT"))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void persistsAndReloadsTicketWithLazyRequesterGeneratedIdEnumsAndTimestamps() {
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.IN_PROGRESS, TicketPriority.HIGH, TicketCategory.INCIDENT, requester);

        ticketRepository.saveAndFlush(ticket);
        assertThat(ticket.getId()).isPositive();
        entityManager.clear();

        Ticket loaded = ticketRepository.findById(ticket.getId()).orElseThrow();

        assertThat(loaded.getTitle()).isEqualTo("Printer offline");
        assertThat(loaded.getDescription()).isEqualTo("The office printer is unreachable.");
        assertThat(loaded.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(loaded.getPriority()).isEqualTo(TicketPriority.HIGH);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isEqualTo(loaded.getCreatedAt());
        assertThat(Hibernate.isInitialized(loaded.getRequester())).isFalse();
        assertThat(loaded.getRequester().getId()).isEqualTo(requester.getId());
        assertThat(loaded.getRequester().getEmail()).isEqualTo(requester.getEmail());
        assertThat(userAccountRepository.count()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("SELECT status, priority, requester_id FROM tickets WHERE id = ?",
                ticket.getId()))
                .containsEntry("status", "IN_PROGRESS")
                .containsEntry("priority", "HIGH")
                .containsEntry("requester_id", requester.getId());
    }

    @Test
    void loadsHistoricalTicketWithoutRequester() {
        // V4 intentionally preserves historical Tickets without inventing an owner.
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tickets (category, title, description, status, priority, created_at, updated_at, version)
                VALUES ('OTHER', 'Historical ticket', 'Unknown requester', 'OPEN', 'LOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                RETURNING id
                """, Long.class);
        entityManager.clear();

        Ticket historical = ticketRepository.findById(id).orElseThrow();

        assertThat(historical.getTitle()).isEqualTo("Historical ticket");
        assertThat(historical.getRequester()).isNull();
    }

    @Test
    void rejectsNonexistentRequesterThroughSql() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO tickets (category, title, description, status, priority, created_at, updated_at, requester_id, version)
                VALUES ('OTHER', 'Invalid owner', 'Missing User', 'OPEN', 'HIGH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, -1, 0)
                """))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOfSatisfying(PSQLException.class, exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23503");
                    assertThat(exception.getServerErrorMessage().getConstraint()).isEqualTo("fk_tickets_requester");
                });
    }

    @Test
    void deletingTicketDoesNotDeleteRequester() {
        Ticket ticket = ticketRepository.saveAndFlush(new Ticket("Printer offline", "No connection",
                TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, requester));

        ticketRepository.delete(ticket);
        ticketRepository.flush();
        entityManager.clear();

        assertThat(ticketRepository.findById(ticket.getId())).isEmpty();
        assertThat(userAccountRepository.findById(requester.getId())).isPresent();
    }

    @Test
    void updatesModificationTimeWhilePreservingCreationTime() {
        Ticket ticket = ticketRepository.saveAndFlush(new Ticket("Printer offline", "Original description",
                TicketStatus.OPEN, TicketPriority.MEDIUM, TicketCategory.INCIDENT, requester));

        Ticket existing = reloadWithHistoricalTimestamps(ticket);
        Instant createdAt = existing.getCreatedAt();
        Instant updatedAt = existing.getUpdatedAt();

        existing.updateDetails("Printer connection issue", "Updated description");
        ticketRepository.flush();
        entityManager.clear();

        Ticket updated = ticketRepository.findById(ticket.getId()).orElseThrow();

        assertThat(updated.getTitle()).isEqualTo("Printer connection issue");
        assertThat(updated.getDescription()).isEqualTo("Updated description");
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isAfter(updatedAt);
    }

    @Test
    void persistsStatusChangeThroughDirtyCheckingAndAdvancesOnlyUpdatedAt() {
        Ticket ticket = ticketRepository.saveAndFlush(new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, requester));
        Ticket existing = reloadWithHistoricalTimestamps(ticket);
        Instant createdAt = existing.getCreatedAt();
        Instant updatedAt = existing.getUpdatedAt();
        assertThat(entityManager.contains(existing)).isTrue();

        Ticket result = ticketService.changeStatus(existing.getId(), TicketStatus.IN_PROGRESS, requester.getEmail());

        assertThat(result).isSameAs(existing);
        ticketRepository.flush();
        entityManager.clear();

        Ticket reloaded = ticketRepository.findById(ticket.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getUpdatedAt()).isAfter(updatedAt);
        assertThat(reloaded.getTitle()).isEqualTo("Printer offline");
        assertThat(reloaded.getDescription()).isEqualTo("The office printer is unreachable.");
        assertThat(reloaded.getPriority()).isEqualTo(TicketPriority.HIGH);
        assertThat(reloaded.getRequester().getId()).isEqualTo(requester.getId());
        assertThat(jdbcTemplate.queryForObject("SELECT requester_id FROM tickets WHERE id = ?",
                Long.class, ticket.getId())).isEqualTo(requester.getId());
    }

    @Test
    void rejectedStatusChangeLeavesPersistedStatusAndTimestampsUnchanged() {
        Ticket ticket = ticketRepository.saveAndFlush(new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, requester));
        Ticket existing = reloadWithHistoricalTimestamps(ticket);
        Instant createdAt = existing.getCreatedAt();
        Instant updatedAt = existing.getUpdatedAt();

        // Flush after the domain rejection so rollback cannot hide an accidental mutation.
        assertThatThrownBy(() -> existing.changeStatus(TicketStatus.CLOSED))
                .isInstanceOfSatisfying(InvalidTicketStatusTransitionException.class, exception -> {
                    assertThat(exception.getTicketId()).isEqualTo(existing.getId());
                    assertThat(exception.getCurrentStatus()).isEqualTo(TicketStatus.OPEN);
                    assertThat(exception.getRequestedStatus()).isEqualTo(TicketStatus.CLOSED);
                });
        ticketRepository.flush();
        entityManager.clear();

        Ticket reloaded = ticketRepository.findById(ticket.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void pagesTicketsByCreationTimeDescendingWithIdAsTieBreaker() {
        Ticket oldest = new Ticket("Oldest", "Oldest ticket", TicketStatus.OPEN, TicketPriority.LOW, TicketCategory.INCIDENT, requester);
        Ticket firstAtLatestTime = new Ticket("Latest first", "First at latest time",
                TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, requester);
        Ticket secondAtLatestTime = new Ticket("Latest second", "Second at latest time",
                TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, requester);
        Ticket middle = new Ticket("Middle", "Middle ticket", TicketStatus.OPEN, TicketPriority.MEDIUM, TicketCategory.INCIDENT, requester);
        ticketRepository.saveAllAndFlush(List.of(oldest, firstAtLatestTime, secondAtLatestTime, middle));

        jdbcTemplate.update("""
                UPDATE tickets SET created_at = TIMESTAMPTZ '2000-01-01 00:00:00+00' WHERE id = ?
                """, oldest.getId());
        jdbcTemplate.update("""
                UPDATE tickets SET created_at = TIMESTAMPTZ '2001-01-01 00:00:00+00' WHERE id = ?
                """, middle.getId());
        jdbcTemplate.update("""
                UPDATE tickets SET created_at = TIMESTAMPTZ '2002-01-01 00:00:00+00' WHERE id IN (?, ?)
                """, firstAtLatestTime.getId(), secondAtLatestTime.getId());
        entityManager.clear();

        Page<Ticket> firstPage = ticketService.listTickets(0, 2, null, null, null, null,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC, requester.getEmail());
        Page<Ticket> secondPage = ticketService.listTickets(1, 2, null, null, null, null,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC, requester.getEmail());

        assertThat(firstPage.getContent()).extracting(Ticket::getId)
                .containsExactly(secondAtLatestTime.getId(), firstAtLatestTime.getId());
        assertThat(secondPage.getContent()).extracting(Ticket::getId)
                .containsExactly(middle.getId(), oldest.getId());
        assertThat(firstPage.getTotalElements()).isEqualTo(4);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.isFirst()).isTrue();
        assertThat(firstPage.isLast()).isFalse();
        assertThat(secondPage.getNumber()).isEqualTo(1);
        assertThat(secondPage.getTotalElements()).isEqualTo(4);
        assertThat(secondPage.isLast()).isTrue();
    }

    @ParameterizedTest(name = "filters status={0}, priority={1} before pagination")
    @MethodSource("filterCombinations")
    void filtersBeforePaginationWithDeterministicOrder(
            TicketStatus status, TicketPriority priority, List<String> expectedTitles) {
        persistFilterFixtures();

        Page<Ticket> firstPage = ticketService.listTickets(0, 2, status, priority, null, null,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC, requester.getEmail());
        Page<Ticket> secondPage = ticketService.listTickets(1, 2, status, priority, null, null,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC, requester.getEmail());

        assertThat(firstPage.getContent()).extracting(Ticket::getTitle)
                .containsExactlyElementsOf(expectedTitles.subList(0, 2));
        assertThat(secondPage.getContent()).extracting(Ticket::getTitle)
                .containsExactlyElementsOf(expectedTitles.subList(2, expectedTitles.size()));
        assertThat(firstPage.getNumber()).isZero();
        assertThat(firstPage.getSize()).isEqualTo(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(expectedTitles.size());
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.isFirst()).isTrue();
        assertThat(firstPage.isLast()).isFalse();
        assertThat(secondPage.getNumber()).isEqualTo(1);
        assertThat(secondPage.getSize()).isEqualTo(2);
        assertThat(secondPage.getTotalElements()).isEqualTo(expectedTitles.size());
        assertThat(secondPage.getTotalPages()).isEqualTo(2);
        assertThat(secondPage.isFirst()).isFalse();
        assertThat(secondPage.isLast()).isTrue();
    }

    static Stream<Arguments> filterCombinations() {
        return Stream.of(
                Arguments.of(TicketStatus.OPEN, null,
                        List.of("Second tied open/high", "First tied open/high", "Open/low", "Old open/high")),
                Arguments.of(null, TicketPriority.HIGH,
                        List.of("In progress/high", "Second tied open/high", "First tied open/high", "Old open/high")),
                Arguments.of(TicketStatus.OPEN, TicketPriority.HIGH,
                        List.of("Second tied open/high", "First tied open/high", "Old open/high"))
        );
    }

    @Test
    void returnsEmptyPageWhenNoTicketMatchesBothFilters() {
        persistFilterFixtures();

        Page<Ticket> page = ticketService.listTickets(0, 2, TicketStatus.CLOSED, TicketPriority.HIGH, null, null,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC, requester.getEmail());

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        assertThat(page.getTotalPages()).isZero();
        assertThat(page.isFirst()).isTrue();
        assertThat(page.isLast()).isTrue();
    }

    @ParameterizedTest(name = "sort={0} {1}, status={2}, priority={3}")
    @MethodSource("customSortCases")
    void sortsAndFiltersBeforePaginationWithMatchingIdTieBreaker(
            TicketSortField sortField, TicketSortDirection direction,
            TicketStatus status, TicketPriority priority, List<Integer> expectedIndexes) {
        List<Ticket> fixtures = persistSortingFixtures();
        List<Long> expectedIds = expectedIndexes.stream().map(index -> fixtures.get(index).getId()).toList();
        assertTicketPages(expectedIds, status, priority, null, sortField, direction);
    }

    static Stream<Arguments> customSortCases() {
        return Stream.of(
                Arguments.of(TicketSortField.CREATED_AT, TicketSortDirection.ASC, null, null,
                        List.of(3, 1, 2, 4, 0, 5)),
                Arguments.of(TicketSortField.UPDATED_AT, TicketSortDirection.DESC, null, null,
                        List.of(5, 3, 2, 0, 4, 1)),
                Arguments.of(TicketSortField.TITLE, TicketSortDirection.ASC, null, null,
                        List.of(1, 0, 2, 3, 4, 5)),
                Arguments.of(TicketSortField.UPDATED_AT, TicketSortDirection.ASC, TicketStatus.OPEN, null,
                        List.of(1, 4, 0, 2, 5)),
                Arguments.of(TicketSortField.TITLE, TicketSortDirection.DESC, null, TicketPriority.HIGH,
                        List.of(5, 4, 3, 2, 0)),
                Arguments.of(TicketSortField.UPDATED_AT, TicketSortDirection.DESC, TicketStatus.OPEN, TicketPriority.HIGH,
                        List.of(5, 2, 0, 4))
        );
    }

    @ParameterizedTest(name = "q={0}, status={1}, priority={2}")
    @MethodSource("searchCases")
    void searchesTitleOrDescriptionWithFiltersBeforeSortedPagination(
            String query, TicketStatus status, TicketPriority priority, List<Integer> expectedIndexes) {
        List<Ticket> fixtures = persistSearchFixtures();
        List<Long> expectedIds = expectedIndexes.stream().map(index -> fixtures.get(index).getId()).toList();

        assertTicketPages(expectedIds, status, priority, query, TicketSortField.TITLE, TicketSortDirection.ASC);
    }

    static Stream<Arguments> searchCases() {
        return Stream.of(
                Arguments.of("LoGiN", null, null, List.of(0, 5, 1, 2, 3)),
                Arguments.of("login", TicketStatus.OPEN, null, List.of(0, 5, 1, 2)),
                Arguments.of("LOGIN", null, TicketPriority.HIGH, List.of(0, 5, 1, 3)),
                Arguments.of("login", TicketStatus.OPEN, TicketPriority.HIGH, List.of(0, 5, 1)),
                Arguments.of("missing", null, null, List.of())
        );
    }

    @ParameterizedTest(name = "literal search for {0}")
    @MethodSource("literalSearchCases")
    void treatsLikeWildcardsAndEscapeCharacterLiterally(
            String query, String matchingTitle, String matchingDescription, String decoyTitle, String decoyDescription) {
        Ticket matching = new Ticket(matchingTitle, matchingDescription,
                TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, requester);
        Ticket decoy = new Ticket(decoyTitle, decoyDescription, TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, requester);
        ticketRepository.saveAllAndFlush(List.of(matching, decoy));
        entityManager.clear();

        Page<Ticket> page = ticketService.listTickets(0, 20, null, null, null, query,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC, requester.getEmail());

        assertThat(page.getContent()).extracting(Ticket::getId).containsExactly(matching.getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getTotalPages()).isEqualTo(1);
    }

    static Stream<Arguments> literalSearchCases() {
        return Stream.of(
                Arguments.of("100%", "Progress at 100% complete", "Literal percentage marker",
                        "Progress at 1000 complete", "No percentage marker"),
                Arguments.of("user_name", "Account field", "Missing USER_NAME value",
                        "Another account field", "Missing userXname value"),
                Arguments.of("folder\\", "Archive", "Path ends in folder\\",
                        "Archive decoy", "Path ends in folder%")
        );
    }

    @ParameterizedTest(name = "rejects invalid enum value using {2}")
    @CsvSource({
            "INVALID, HIGH, ck_tickets_status",
            "OPEN, INVALID, ck_tickets_priority"
    })
    void rejectsInvalidEnumValuesThroughSql(String status, String priority, String constraintName) {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO tickets (category, title, description, status, priority, created_at, updated_at, version)
                VALUES ('OTHER', ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """, "Invalid ticket", "Invalid enum value", status, priority))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOfSatisfying(PSQLException.class, exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23514");
                    assertThat(exception.getServerErrorMessage().getConstraint()).isEqualTo(constraintName);
                });
    }

    private void assertTicketPages(List<Long> expectedIds, TicketStatus status, TicketPriority priority, String query,
            TicketSortField sortField, TicketSortDirection direction) {
        int size = 2;
        int totalPages = Math.ceilDiv(expectedIds.size(), size);
        int pagesToRead = Math.max(1, totalPages);

        for (int pageNumber = 0; pageNumber < pagesToRead; pageNumber++) {
            Page<Ticket> page = ticketService.listTickets(pageNumber, size, status, priority, null, query,
                    sortField, direction, requester.getEmail());

            int start = pageNumber * size;
            int end = Math.min(start + size, expectedIds.size());
            assertThat(page.getContent()).extracting(Ticket::getId)
                    .containsExactlyElementsOf(expectedIds.subList(start, end));
            assertThat(page.getNumber()).isEqualTo(pageNumber);
            assertThat(page.getSize()).isEqualTo(size);
            assertThat(page.getTotalElements()).isEqualTo(expectedIds.size());
            assertThat(page.getTotalPages()).isEqualTo(totalPages);
            assertThat(page.isFirst()).isEqualTo(pageNumber == 0);
            assertThat(page.isLast()).isEqualTo(pageNumber == pagesToRead - 1);
        }
    }

    private List<Ticket> persistSearchFixtures() {
        List<Ticket> fixtures = ticketRepository.saveAllAndFlush(List.of(
                new Ticket("Alpha LOGIN failure", "Account cannot be reached.",
                        TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, requester),
                new Ticket("Bravo access issue", "User reports a login failure.",
                        TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, requester),
                new Ticket("Charlie account issue", "Another login problem.",
                        TicketStatus.OPEN, TicketPriority.LOW, TicketCategory.INCIDENT, requester),
                new Ticket("Delta permissions", "LOGIN fails after reset.",
                        TicketStatus.CLOSED, TicketPriority.HIGH, TicketCategory.INCIDENT, requester),
                new Ticket("Echo network issue", "Connection reset before sign-in.",
                        TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, requester),
                new Ticket("Alpha LOGIN failure", "Another LOGIN problem.",
                        TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, requester)
        ));
        entityManager.clear();
        return fixtures;
    }

    private Ticket reloadWithHistoricalTimestamps(Ticket ticket) {
        // Model an older row so the update assertion does not depend on clock resolution.
        jdbcTemplate.update("""
                UPDATE tickets
                SET created_at = TIMESTAMPTZ '2000-01-01 00:00:00+00',
                    updated_at = TIMESTAMPTZ '2000-01-01 00:00:00+00'
                WHERE id = ?
                """, ticket.getId());
        entityManager.clear();
        return ticketRepository.findById(ticket.getId()).orElseThrow();
    }

    private void persistFilterFixtures() {
        persistFilterTicket("Old open/high", TicketStatus.OPEN, TicketPriority.HIGH, "2000-01-01T00:00:00Z");
        persistFilterTicket("First tied open/high", TicketStatus.OPEN, TicketPriority.HIGH, "2002-01-01T00:00:00Z");
        persistFilterTicket("Second tied open/high", TicketStatus.OPEN, TicketPriority.HIGH, "2002-01-01T00:00:00Z");
        persistFilterTicket("Open/low", TicketStatus.OPEN, TicketPriority.LOW, "2001-01-01T00:00:00Z");
        persistFilterTicket("In progress/high", TicketStatus.IN_PROGRESS, TicketPriority.HIGH,
                "2003-01-01T00:00:00Z");
        persistFilterTicket("Closed/urgent", TicketStatus.CLOSED, TicketPriority.URGENT, "2004-01-01T00:00:00Z");
        entityManager.clear();
    }

    private void persistFilterTicket(String title, TicketStatus status, TicketPriority priority, String createdAt) {
        Ticket ticket = ticketRepository.saveAndFlush(new Ticket(title, "Filter fixture", status, priority, TicketCategory.INCIDENT, requester));
        jdbcTemplate.update("UPDATE tickets SET created_at = CAST(? AS TIMESTAMPTZ) WHERE id = ?",
                createdAt, ticket.getId());
    }

    private List<Ticket> persistSortingFixtures() {
        List<Ticket> fixtures = List.of(
                persistSortingTicket("Bravo", TicketStatus.OPEN, TicketPriority.HIGH,
                        "2002-01-01T00:00:00Z", "2012-01-01T00:00:00Z"),
                persistSortingTicket("Alpha", TicketStatus.OPEN, TicketPriority.LOW,
                        "2000-01-01T00:00:00Z", "2010-01-01T00:00:00Z"),
                persistSortingTicket("Bravo", TicketStatus.OPEN, TicketPriority.HIGH,
                        "2001-01-01T00:00:00Z", "2012-01-01T00:00:00Z"),
                persistSortingTicket("Charlie", TicketStatus.CLOSED, TicketPriority.HIGH,
                        "1999-01-01T00:00:00Z", "2013-01-01T00:00:00Z"),
                persistSortingTicket("Delta", TicketStatus.OPEN, TicketPriority.HIGH,
                        "2001-01-01T00:00:00Z", "2011-01-01T00:00:00Z"),
                persistSortingTicket("Echo", TicketStatus.OPEN, TicketPriority.HIGH,
                        "2003-01-01T00:00:00Z", "2014-01-01T00:00:00Z")
        );
        entityManager.clear();
        return fixtures;
    }

    private Ticket persistSortingTicket(String title, TicketStatus status, TicketPriority priority,
            String createdAt, String updatedAt) {
        Ticket ticket = ticketRepository.saveAndFlush(
                new Ticket(title, "Sorting fixture", status, priority, TicketCategory.INCIDENT, requester));
        jdbcTemplate.update("""
                UPDATE tickets SET created_at = CAST(? AS TIMESTAMPTZ), updated_at = CAST(? AS TIMESTAMPTZ) WHERE id = ?
                """, createdAt, updatedAt, ticket.getId());
        return ticket;
    }

}
