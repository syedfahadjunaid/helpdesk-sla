package com.helpdesk.sla;

import com.helpdesk.ticket.Priority;
import org.springframework.data.jpa.repository.JpaRepository;

/** SLA policies, one per priority. (An interface only: Spring Data writes the implementation.) */
public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, Priority> {}
