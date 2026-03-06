package com.whut.map.listener.repository;

import com.whut.map.listener.entity.AisMessage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AisMessageRepository extends JpaRepository<AisMessage, Long> {
}