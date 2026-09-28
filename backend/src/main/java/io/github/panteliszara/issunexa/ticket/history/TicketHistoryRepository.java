package io.github.panteliszara.issunexa.ticket.history;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketHistoryRepository extends JpaRepository<TicketHistoryEntry, Long> {

    @EntityGraph(attributePaths = {"actor", "assignee"})
    Page<TicketHistoryEntry> findByTicketId(Long ticketId, Pageable pageable);

}
