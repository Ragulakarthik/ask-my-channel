package com.karthik.askmychannel.repository;

import com.karthik.askmychannel.entity.AppSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingsRepository extends JpaRepository<AppSettings, Short> {
}
