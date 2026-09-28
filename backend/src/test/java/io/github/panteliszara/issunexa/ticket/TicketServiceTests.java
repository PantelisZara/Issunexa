package io.github.panteliszara.issunexa.ticket;

import io.github.panteliszara.issunexa.ticket.history.TicketHistoryEntry;
import io.github.panteliszara.issunexa.ticket.history.TicketHistoryRepository;
import io.github.panteliszara.issunexa.ticket.history.TicketHistoryType;
import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserAccountRepository;
import io.github.panteliszara.issunexa.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketServiceTests {

    private static final String EMAIL = "alice@example.com";

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private TicketHistoryRepository historyRepository;

    private final UserAccount requester = new UserAccount("alice@example.com", "Alice",
            "{bcrypt}encoded-test-value", UserRole.REQUESTER);

    private TicketService ticketService;

    @BeforeEach
    void setUp() {
        ticketService = new TicketService(ticketRepository, userAccountRepository, historyRepository);
        ReflectionTestUtils.setField(requester, "id", 7L);
    }

    @Test
    void resolvesCanonicalRequesterCreatesOpenTicketAndReturnsRepositoryResult() {
        String title = "Printer offline";
        String description = "The office printer is unreachable.\nIt shows a network error.";
        TicketPriority priority = TicketPriority.HIGH;
        Ticket persistedTicket = new Ticket(title, description, TicketStatus.OPEN, priority, requester);
        ReflectionTestUtils.setField(persistedTicket, "id", 42L);
        when(userAccountRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(requester));
        when(ticketRepository.save(any(Ticket.class))).thenReturn(persistedTicket);

        Ticket result = ticketService.createTicket(title, description, priority, " \tAlice@Example.COM\n ");

        verify(userAccountRepository).findByEmail("alice@example.com");
        ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository).save(ticketCaptor.capture());
        Ticket submittedTicket = ticketCaptor.getValue();
        assertThat(submittedTicket.getTitle()).isEqualTo(title);
        assertThat(submittedTicket.getDescription()).isEqualTo(description);
        assertThat(submittedTicket.getPriority()).isEqualTo(priority);
        assertThat(submittedTicket.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(submittedTicket.getRequester()).isSameAs(requester);
        assertThat(result).isSameAs(persistedTicket);
        var calls = inOrder(ticketRepository, historyRepository);
        calls.verify(ticketRepository).save(any(Ticket.class));
        ArgumentCaptor<TicketHistoryEntry> history = ArgumentCaptor.forClass(TicketHistoryEntry.class);
        calls.verify(historyRepository).save(history.capture());
        assertThat(history.getValue().getType()).isEqualTo(TicketHistoryType.TICKET_CREATED);
        assertThat(history.getValue().getTicket()).isSameAs(persistedTicket);
        assertThat(history.getValue().getActor()).isSameAs(requester);
        assertThat(history.getValue().getNewStatus()).isEqualTo(TicketStatus.OPEN);
        verifyNoMoreInteractions(userAccountRepository, ticketRepository);
    }

    @Test
    void failsWithoutPersistingTicketWhenAuthenticatedAccountIsMissing() {
        when(userAccountRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.createTicket("Printer offline", "The printer is unreachable.",
                TicketPriority.HIGH, "missing@example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Authenticated user account could not be resolved.");
        verify(userAccountRepository).findByEmail("missing@example.com");
        verifyNoMoreInteractions(userAccountRepository);
        verifyNoInteractions(ticketRepository, historyRepository);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"AGENT", "ADMIN"})
    void returnsTicketUsingUnrestrictedLookupForStaff(UserRole role) {
        stubActor(role);
        Long id = 42L;
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.IN_PROGRESS, TicketPriority.HIGH, requester);
        when(ticketRepository.findById(id)).thenReturn(Optional.of(ticket));

        Ticket result = ticketService.getTicket(id, EMAIL);

        assertThat(result).isSameAs(ticket);
        verify(ticketRepository).findById(id);
        verify(userAccountRepository).findByEmail(EMAIL);
        verifyNoMoreInteractions(ticketRepository);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"AGENT", "ADMIN"})
    void throwsNotFoundExceptionForMissingStaffLookup(UserRole role) {
        stubActor(role);
        Long missingId = 99L;
        when(ticketRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.getTicket(missingId, EMAIL))
                .isInstanceOf(TicketNotFoundException.class)
                .hasMessageContaining(missingId.toString());
        verify(ticketRepository).findById(missingId);
    }

    @Test
    void queriesRequesterTicketByIdAndOwnership() {
        stubActor(UserRole.REQUESTER);
        Ticket ticket = new Ticket("Printer offline", "No connection", TicketStatus.OPEN, TicketPriority.HIGH, requester);
        when(ticketRepository.findOne(ArgumentMatchers.<Specification<Ticket>>any())).thenReturn(Optional.of(ticket));

        assertThat(ticketService.getTicket(42L, " \tAlice@Example.COM\n ")).isSameAs(ticket);

        verify(userAccountRepository).findByEmail(EMAIL);
        verify(ticketRepository).findOne(ArgumentMatchers.<Specification<Ticket>>notNull());
        verifyNoMoreInteractions(ticketRepository);
    }

    @Test
    void mapsInvisibleOrMissingScopedResultToExistingNotFoundException() {
        stubActor(UserRole.REQUESTER);
        when(ticketRepository.findOne(ArgumentMatchers.<Specification<Ticket>>any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.getTicket(42L, EMAIL))
                .isInstanceOf(TicketNotFoundException.class)
                .hasMessage("Ticket with ID 42 was not found");
        verify(ticketRepository).findOne(ArgumentMatchers.<Specification<Ticket>>notNull());
        verifyNoMoreInteractions(ticketRepository);
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void resolvesEachRoleAndExecutesSpecificationListing(UserRole role) {
        stubActor(role);
        Page<Ticket> page = Page.empty();
        when(ticketRepository.findAll(ArgumentMatchers.<Specification<Ticket>>any(), any(Pageable.class)))
                .thenReturn(page);

        assertThat(ticketService.listTickets(0, 20, null, null, null,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC, EMAIL)).isSameAs(page);

        verify(userAccountRepository).findByEmail(EMAIL);
        verify(ticketRepository).findAll(ArgumentMatchers.<Specification<Ticket>>notNull(), any(Pageable.class));
        verifyNoMoreInteractions(ticketRepository);
    }

    @Test
    void rejectsNullRoleBeforeReadingTickets() {
        ReflectionTestUtils.setField(requester, "role", null);
        when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(requester));

        assertThatThrownBy(() -> ticketService.getTicket(42L, EMAIL)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ticketService.listTickets(0, 20, null, null, null,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC, EMAIL)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(ticketRepository);
    }

    @Test
    void rejectsUnresolvedActorBeforeReadingTickets() {
        when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.getTicket(42L, EMAIL)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ticketService.listTickets(0, 20, null, null, null,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC, EMAIL)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ticketRepository);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"AGENT", "ADMIN"})
    void claimsForCanonicalActorAndReturnsManagedTicketWithoutSaving(UserRole role) {
        UserAccount actor = new UserAccount(EMAIL, "Alice Staff", "encoded-test-value", role);
        Ticket ticket = new Ticket("Printer offline", "No connection", TicketStatus.OPEN, TicketPriority.HIGH, requester);
        when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(actor));
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(ticket));

        assertThat(ticketService.claimTicket(42L, " \tAlice@Example.COM\n ")).isSameAs(ticket);

        assertThat(ticket.getAssignee()).isSameAs(actor);
        ArgumentCaptor<TicketHistoryEntry> history = ArgumentCaptor.forClass(TicketHistoryEntry.class);
        verify(historyRepository).save(history.capture());
        assertThat(history.getValue().getType()).isEqualTo(TicketHistoryType.ASSIGNEE_CLAIMED);
        assertThat(history.getValue().getTicket()).isSameAs(ticket);
        assertThat(history.getValue().getActor()).isSameAs(actor);
        assertThat(history.getValue().getAssignee()).isSameAs(actor);
        verify(userAccountRepository).findByEmail(EMAIL);
        verify(ticketRepository).findById(42L);
        verifyNoMoreInteractions(userAccountRepository, ticketRepository);
    }

    @Test
    void missingClaimTargetUsesExistingNotFoundException() {
        stubActor(UserRole.AGENT);
        when(ticketRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.claimTicket(99L, EMAIL))
                .isInstanceOf(TicketNotFoundException.class).hasMessage("Ticket with ID 99 was not found");
        verifyNoInteractions(historyRepository);
        verify(userAccountRepository).findByEmail(EMAIL);
        verify(ticketRepository).findById(99L);
        verifyNoMoreInteractions(userAccountRepository, ticketRepository);
    }

    @Test
    void propagatesAlreadyAssignedConflictWithoutSaving() {
        stubActor(UserRole.ADMIN);
        Ticket ticket = new Ticket("Printer offline", "No connection", TicketStatus.OPEN, TicketPriority.HIGH, requester);
        UserAccount original = new UserAccount("agent@example.com", "Agent", "encoded-test-value", UserRole.AGENT);
        ticket.claim(original);
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> ticketService.claimTicket(42L, EMAIL))
                .isInstanceOf(TicketAlreadyAssignedException.class);
        assertThat(ticket.getAssignee()).isSameAs(original);
        verifyNoInteractions(historyRepository);
        verify(userAccountRepository).findByEmail(EMAIL);
        verify(ticketRepository).findById(42L);
        verifyNoMoreInteractions(userAccountRepository, ticketRepository);
    }

    @Test
    void changesStatusAndReturnsLoadedTicketWithoutSavingAgain() {
        UserAccount actor = new UserAccount(EMAIL, "Agent", "test-hash", UserRole.AGENT);
        when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(actor));
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.OPEN, TicketPriority.HIGH, requester);
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(ticket));

        Ticket result = ticketService.changeStatus(42L, TicketStatus.IN_PROGRESS, EMAIL);

        assertThat(result).isSameAs(ticket);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        ArgumentCaptor<TicketHistoryEntry> history = ArgumentCaptor.forClass(TicketHistoryEntry.class);
        verify(historyRepository).save(history.capture());
        assertThat(history.getValue().getType()).isEqualTo(TicketHistoryType.STATUS_CHANGED);
        assertThat(history.getValue().getTicket()).isSameAs(ticket);
        assertThat(history.getValue().getActor()).isSameAs(actor);
        assertThat(history.getValue().getPreviousStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(history.getValue().getNewStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        verify(ticketRepository).findById(42L);
        verifyNoMoreInteractions(ticketRepository);
    }

    @Test
    void rejectsStatusChangeWhenTicketIsMissing() {
        stubActor(UserRole.AGENT);
        when(ticketRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.changeStatus(99L, TicketStatus.IN_PROGRESS, EMAIL))
                .isInstanceOf(TicketNotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(historyRepository);
        verify(ticketRepository).findById(99L);
        verifyNoMoreInteractions(ticketRepository);
    }

    @Test
    void propagatesInvalidStatusTransition() {
        stubActor(UserRole.AGENT);
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.OPEN, TicketPriority.HIGH, requester);
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> ticketService.changeStatus(42L, TicketStatus.CLOSED, EMAIL))
                .isInstanceOf(InvalidTicketStatusTransitionException.class);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
        verifyNoInteractions(historyRepository);
        verify(ticketRepository).findById(42L);
        verifyNoMoreInteractions(ticketRepository);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("sortOrders")
    void listsTicketsWithPaginationAndDeterministicOrder(
            TicketSortField sortField, TicketSortDirection direction, Sort.Order primaryOrder, Sort.Order idOrder) {
        stubActor(UserRole.REQUESTER);
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.OPEN, TicketPriority.HIGH, requester);
        Page<Ticket> repositoryResult = new PageImpl<>(List.of(ticket), PageRequest.of(2, 10), 21);
        when(ticketRepository.findAll(ArgumentMatchers.<Specification<Ticket>>any(), any(Pageable.class)))
                .thenReturn(repositoryResult);

        Page<Ticket> result = ticketService.listTickets(2, 10, null, null, null, sortField, direction, EMAIL);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(ticketRepository).findAll(ArgumentMatchers.<Specification<Ticket>>notNull(), pageableCaptor.capture());
        assertListingPageable(pageableCaptor.getValue(), primaryOrder, idOrder);
        assertThat(result).isSameAs(repositoryResult);
        verifyNoMoreInteractions(ticketRepository);
    }

    @Test
    void listsTicketsWithAllSearchCriteriaAndReturnsRepositoryPage() {
        stubActor(UserRole.REQUESTER);
        Ticket ticket = new Ticket("Login failure", "The account is unreachable.",
                TicketStatus.IN_PROGRESS, TicketPriority.URGENT, requester);
        Page<Ticket> repositoryResult = new PageImpl<>(List.of(ticket), PageRequest.of(2, 10), 21);
        when(ticketRepository.findAll(ArgumentMatchers.<Specification<Ticket>>any(), any(Pageable.class)))
                .thenReturn(repositoryResult);

        Page<Ticket> result = ticketService.listTickets(2, 10, TicketStatus.IN_PROGRESS, TicketPriority.URGENT, "login",
                TicketSortField.UPDATED_AT, TicketSortDirection.ASC, EMAIL);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(ticketRepository).findAll(ArgumentMatchers.<Specification<Ticket>>notNull(), pageableCaptor.capture());
        assertListingPageable(pageableCaptor.getValue(), Sort.Order.asc("updatedAt"), Sort.Order.asc("id"));
        assertThat(result).isSameAs(repositoryResult);
        verifyNoMoreInteractions(ticketRepository);
    }

    static Stream<Arguments> sortOrders() {
        return Stream.of(
                Arguments.of(TicketSortField.CREATED_AT, TicketSortDirection.DESC,
                        Sort.Order.desc("createdAt"), Sort.Order.desc("id")),
                Arguments.of(TicketSortField.CREATED_AT, TicketSortDirection.ASC,
                        Sort.Order.asc("createdAt"), Sort.Order.asc("id")),
                Arguments.of(TicketSortField.UPDATED_AT, TicketSortDirection.DESC,
                        Sort.Order.desc("updatedAt"), Sort.Order.desc("id")),
                Arguments.of(TicketSortField.UPDATED_AT, TicketSortDirection.ASC,
                        Sort.Order.asc("updatedAt"), Sort.Order.asc("id")),
                Arguments.of(TicketSortField.TITLE, TicketSortDirection.ASC,
                        Sort.Order.asc("title"), Sort.Order.asc("id")),
                Arguments.of(TicketSortField.TITLE, TicketSortDirection.DESC,
                        Sort.Order.desc("title"), Sort.Order.desc("id"))
        );
    }

    private void stubActor(UserRole role) {
        UserAccount actor = new UserAccount(EMAIL, "Alice", "{bcrypt}encoded-test-value", role);
        ReflectionTestUtils.setField(actor, "id", 7L);
        when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(actor));
    }

    private static void assertListingPageable(Pageable pageable, Sort.Order primaryOrder, Sort.Order idOrder) {
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort()).containsExactly(primaryOrder, idOrder);
    }

}
