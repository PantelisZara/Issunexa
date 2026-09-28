package io.github.panteliszara.issunexa.ticket;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket> {

    // Fetch the response's assignee within the service transaction; requester remains lazy.
    @Override
    @EntityGraph(attributePaths = "assignee")
    Optional<Ticket> findById(Long id);

    @Override
    @EntityGraph(attributePaths = "assignee")
    Optional<Ticket> findOne(Specification<Ticket> specification);

    @Override
    @EntityGraph(attributePaths = "assignee")
    Page<Ticket> findAll(Specification<Ticket> specification, Pageable pageable);

}
