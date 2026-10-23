package com.aimastery.gateway;

import com.aimastery.gateway.config.GatewayProperties;
import com.aimastery.gateway.config.PricingProperties;
import com.aimastery.gateway.config.ResilienceProperties;
import com.aimastery.gateway.config.SettingsProperties;
import com.aimastery.gateway.eval.EvalProperties;
import com.aimastery.gateway.eval.gate.GateProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({GatewayProperties.class, PricingProperties.class, EvalProperties.class,
        SettingsProperties.class, ResilienceProperties.class, GateProperties.class})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
