package com.hmall.gateway.config;

import com.hmall.common.jwt.JwtProperties;
import com.hmall.common.jwt.JwtTool;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.rsa.crypto.KeyStoreKeyFactory;

import java.security.KeyPair;

/**
 * 网关侧的 JWT 配置：只加载公钥/私钥对，用于校验 token 签名，不再往业务服务里放私钥。
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    @Bean
    public KeyPair keyPair(JwtProperties properties) {
        KeyStoreKeyFactory keyStoreKeyFactory =
                new KeyStoreKeyFactory(properties.getLocation(), properties.getPassword().toCharArray());
        return keyStoreKeyFactory.getKeyPair(properties.getAlias(), properties.getPassword().toCharArray());
    }

    @Bean
    public JwtTool jwtTool(KeyPair keyPair) {
        return new JwtTool(keyPair);
    }
}
