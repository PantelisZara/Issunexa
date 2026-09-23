package io.github.panteliszara.issunexa.ticket;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    Page<Ticket> findAllByStatus(TicketStatus status, Pageable pageable);

    Page<Ticket> findAllByPriority(TicketPriority priority, Pageable pageable);

    Page<Ticket> findAllByStatusAndPriority(TicketStatus status, TicketPriority priority, Pageable pageable);

}
