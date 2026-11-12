package com.hmall.sentinel;

import com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 接入：
 * 1. 注册 @SentinelResource 注解切面；
 * 2. 上报到 dashboard（8858），可在控制台实时看 QPS、调整规则；
 * 3. 启动时用代码加载一份基础流控规则，保证换台机器/重启后规则不丢，不依赖手工配置。
 */
@Slf4j
@Configuration
public class SentinelConfig {

    @Value("${hm.sentinel.dashboard:127.0.0.1:8858}")
    private String dashboard;

    @Value("${hm.sentinel.item-qps:30}")
    private double itemDetailQps;

    @Value("${hm.sentinel.order-qps:10}")
    private double createOrderQps;

    @Bean
    public SentinelResourceAspect sentinelResourceAspect() {
        return new SentinelResourceAspect();
    }

    @PostConstruct
    public void initFlowRules() {
        // 与 dashboard 通信：应用侧会开 8720 端口供控制台拉取机器信息
        System.setProperty("csp.sentinel.dashboard.server", dashboard);
        System.setProperty("project.name", "hm-service");
        System.setProperty("csp.sentinel.api.port", "8720");
        System.setProperty("csp.sentinel.log.dir", "logs/sentinel");

        List<FlowRule> rules = new ArrayList<>();
        rules.add(flowRule("itemDetail", itemDetailQps));
        rules.add(flowRule("createOrder", createOrderQps));
        FlowRuleManager.loadRules(rules);
        log.info("Sentinel 流控规则加载完成：itemDetail={} QPS，createOrder={} QPS，dashboard={}", itemDetailQps, createOrderQps, dashboard);
    }

    private FlowRule flowRule(String resource, double count) {
        FlowRule rule = new FlowRule();
        rule.setResource(resource);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(count);
        rule.setLimitApp("default");
        return rule;
    }
}
