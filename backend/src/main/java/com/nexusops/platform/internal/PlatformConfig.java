package com.nexusops.platform.internal;

import com.nexusops.platform.PlatformProperties;
import com.nexusops.platform.totp.TotpSecretCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlatformProperties.class)
class PlatformConfig {

    @Bean
    TotpSecretCipher totpSecretCipher(PlatformProperties properties) {
        return new TotpSecretCipher(properties.totpKey());
    }
}
