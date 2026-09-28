package io.github.panteliszara.issunexa.ticket.comment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketCommentRepository extends JpaRepository<TicketComment, Long> {

    @EntityGraph(attributePaths = "author")
    Page<TicketComment> findByTicketId(Long ticketId, Pageable pageable);

}
