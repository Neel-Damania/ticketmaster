package com.assigment.paytm.ticketMaster.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({HoldProperties.class, JwtProperties.class})
public class AppConfig {
}
