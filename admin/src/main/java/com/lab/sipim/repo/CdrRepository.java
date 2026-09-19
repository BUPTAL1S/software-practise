package com.lab.sipim.repo;

import com.lab.sipim.domain.Cdr;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CdrRepository extends JpaRepository<Cdr, Long> {
}
