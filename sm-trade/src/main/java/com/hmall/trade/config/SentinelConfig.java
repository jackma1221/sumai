package com.hmall.trade.config;

import com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
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
 * 交易服务 Sentinel 配置：
 * <ul>
 *   <li>createOrder：QPS 流控，防止刷单；</li>
 *   <li>remoteItemQuery / remoteItemDeduct：Feign 远程调用的熔断降级，
 *       商品服务异常比例超过 50% 时熔断 10 秒，直接走兜底，避免请求堆积把交易服务拖死。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class SentinelConfig {

    @Value("${hm.sentinel.dashboard:127.0.0.1:8858}")
    private String dashboard;

    @Value("${hm.sentinel.order-qps:10}")
    private double createOrderQps;

    @Bean
    public SentinelResourceAspect sentinelResourceAspect() {
        return new SentinelResourceAspect();
    }

    @PostConstruct
    public void initRules() {
        System.setProperty("csp.sentinel.dashboard.server", dashboard);
        System.setProperty("project.name", "trade-service");
        System.setProperty("csp.sentinel.api.port", "8722");
        System.setProperty("csp.sentinel.log.dir", "logs/sentinel");

        List<FlowRule> flowRules = new ArrayList<>();
        FlowRule create = new FlowRule();
        create.setResource("createOrder");
        create.setGrade(RuleConstant.FLOW_GRADE_QPS);
        create.setCount(createOrderQps);
        create.setLimitApp("default");
        flowRules.add(create);
        FlowRuleManager.loadRules(flowRules);

        List<DegradeRule> degradeRules = new ArrayList<>();
        degradeRules.add(exceptionRatioRule("remoteItemQuery"));
        degradeRules.add(exceptionRatioRule("remoteItemDeduct"));
        DegradeRuleManager.loadRules(degradeRules);

        log.info("Sentinel 规则加载完成：createOrder={} QPS；远程调用熔断 异常比例>50%/10s，dashboard={}",
                createOrderQps, dashboard);
    }

    private DegradeRule exceptionRatioRule(String resource) {
        DegradeRule rule = new DegradeRule();
        rule.setResource(resource);
        rule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        rule.setCount(0.5);           // 异常比例阈值 50%
        rule.setMinRequestAmount(5);  // 至少 5 个请求
        rule.setStatIntervalMs(1000); // 1 秒统计窗口
        rule.setTimeWindow(10);       // 熔断 10 秒
        return rule;
    }
}
