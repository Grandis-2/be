package com.grandis.nova.preorder.campaign.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CampaignProperties.class, CampaignProgressProperties.class, OpenAlertProperties.class})
class CampaignConfig {
}
